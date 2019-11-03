@file:JvmName("SqliteUtils")
package net.aquadc.persistence.sql

import android.database.Cursor
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteProgram
import android.database.sqlite.SQLiteQueryBuilder
import android.database.sqlite.SQLiteStatement
import net.aquadc.persistence.array
import net.aquadc.persistence.struct.Lens
import net.aquadc.persistence.struct.Schema
import net.aquadc.persistence.struct.StoredNamedLens
import net.aquadc.persistence.struct.Struct
import net.aquadc.persistence.struct.approxType
import net.aquadc.persistence.type.DataType
import net.aquadc.persistence.type.long
import net.aquadc.persistence.sql.dialect.sqlite.SqliteDialect
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.getOrSet

/**
 * Represents a connection with an [SQLiteDatabase].
 */
// TODO: use simpleQueryForLong and simpleQueryForString with compiled statements where possible
class SqliteSession(
        @JvmSynthetic @JvmField internal val connection: SQLiteDatabase
) : Session {

    @JvmSynthetic internal val lock = ReentrantReadWriteLock()

    @Suppress("UNCHECKED_CAST")
    override fun <SCH : Schema<SCH>, ID : IdBound, REC : Record<SCH, ID>> get(table: Table<SCH, ID, REC>): Dao<SCH, ID, REC> =
            getDao(table) as Dao<SCH, ID, REC>

    @JvmSynthetic internal fun <SCH : Schema<SCH>, ID : IdBound> getDao(table: Table<SCH, ID, *>): RealDao<SCH, ID, *, SQLiteStatement> =
            lowLevel.daos.getOrPut(table) { RealDao(this, lowLevel, table as Table<SCH, ID, Record<SCH, ID>>, SqliteDialect) } as RealDao<SCH, ID, *, SQLiteStatement>

    // region transactions and modifying statements

    // transactional things, guarded by write-lock
    @JvmSynthetic @JvmField internal var transaction: RealTransaction? = null

    private val lowLevel = object : LowLevelSession<SQLiteStatement> {

        override fun <SCH : Schema<SCH>, ID : IdBound> insert(table: Table<SCH, ID, *>, data: Struct<SCH>): ID {
            val dao = getDao(table)
            val statement = dao.insertStatement ?: connection.compileStatement(SqliteDialect.insert(table)).also { dao.insertStatement = it }

            bindInsertionParams(table, data) { type, idx, value ->
                type.bind(statement, idx, value)
            }
            val id = statement.executeInsert()
            check(id != -1L)
            return id as ID
        }

        private fun <SCH : Schema<SCH>, ID : IdBound> updateStatementWLocked(table: Table<SCH, ID, *>, cols: Any): SQLiteStatement =
                getDao(table)
                        .updateStatements
                        .getOrPut(cols) {
                            val colArray =
                                    if (cols is Array<*>) cols as Array<StoredNamedLens<SCH, *, *>>
                                    else arrayOf(cols as StoredNamedLens<SCH, *, *>)
                            connection.compileStatement(SqliteDialect.updateQuery(table, colArray))
                        }

        override fun <SCH : Schema<SCH>, ID : IdBound> update(table: Table<SCH, ID, *>, id: ID, columns: Any, values: Any?) {
            val statement = updateStatementWLocked(table, columns)
            val colCount = bindValues(columns, values) { type, idx, value ->
                type.bind(statement, idx, value)
            }
            table.idColType.bind(statement, colCount, id)
            check(statement.executeUpdateDelete() == 1)
        }

        override fun <SCH : Schema<SCH>, ID : IdBound> delete(table: Table<SCH, ID, *>, primaryKey: ID) {
            val dao = getDao(table)
            val statement = dao.deleteStatement ?: connection.compileStatement(SqliteDialect.deleteRecordQuery(table)).also { dao.deleteStatement = it }
            table.idColType.bind(statement, 0, primaryKey)
            check(statement.executeUpdateDelete() == 1)
        }

        override fun truncate(table: Table<*, *, *>) {
            connection.execSQL(SqliteDialect.truncate(table))
        }

        override val daos = ConcurrentHashMap<Table<*, *, *>, RealDao<*, *, *, SQLiteStatement>>()

        override fun onTransactionEnd(successful: Boolean) {
            val transaction = transaction ?: throw AssertionError()
            try {
                if (successful) {
                    connection.setTransactionSuccessful()
                }
                connection.endTransaction()
                this@SqliteSession.transaction = null

                if (successful) {
                    transaction.deliverChanges()
                }
            } finally {
                lock.writeLock().unlock()
            }
        }

        private fun <SCH : Schema<SCH>, ID : IdBound> select(
                table: Table<SCH, ID, *>,
                columns: Array<out StoredNamedLens<SCH, *, *>>?,
                condition: WhereCondition<SCH>,
                order: Array<out Order<out SCH>>
        ): Cursor {
            val sql = with(SqliteDialect) {
                SQLiteQueryBuilder.buildQueryString( // fixme: building SQL myself may save some allocations
                        /*distinct=*/false,
                        table.name,
                        if (columns == null) arrayOf("COUNT(*)") else columns.mapIndexedToArray { _, col -> col.name },
                        StringBuilder().appendWhereClause(table, condition).toString(),
                        /*groupBy=*/null,
                        /*having=*/null,
                        if (order.isEmpty()) null else StringBuilder().appendOrderClause(order).toString(),
                        /*limit=*/null
                )
            }

            // a workaround for binding BLOBS, as suggested in https://stackoverflow.com/a/23159664/3050249
            return connection.rawQueryWithFactory(
                    { _, masterQuery, editTable, query ->
                        bindQueryParams(condition, table) { type, idx, value ->
                            type.bind(query, idx, value)
                        }

                        SQLiteCursor(masterQuery, editTable, query)
                    },
                    sql,
                    /*selectionArgs=*/null,
                    SQLiteDatabase.findEditTable(table.name),
                    /*cancellationSignal=*/null
            )
        }

        override fun <SCH : Schema<SCH>, ID : IdBound, T> fetchSingle(
                table: Table<SCH, ID, *>, column: StoredNamedLens<SCH, T, *>, id: ID
        ): T =
                select<SCH, ID>(table, arrayOf(column) /* fixme allocation */, pkCond<SCH, ID>(table, id), NoOrder)
                        .fetchSingle(column.approxType)

        override fun <SCH : Schema<SCH>, ID : IdBound> fetchPrimaryKeys(
                table: Table<SCH, ID, *>, condition: WhereCondition<SCH>, order: Array<out Order<SCH>>
        ): Array<ID> =
                select<SCH, ID>(table, arrayOf(table.pkColumn) /* fixme allocation */, condition, order)
                        .fetchAllRows(table.idColType)
                        .array<Any>() as Array<ID>

        override fun <SCH : Schema<SCH>, ID : IdBound> fetch(
                table: Table<SCH, ID, *>, columns: Array<out StoredNamedLens<SCH, *, *>>, id: ID
        ): Array<Any?> =
                select<SCH, ID>(table, columns, pkCond<SCH, ID>(table, id), NoOrder).fetchColumns(columns)

        override fun <SCH : Schema<SCH>, ID : IdBound> fetchCount(table: Table<SCH, ID, *>, condition: WhereCondition<SCH>): Long =
                select<SCH, ID>(table, null, condition, NoOrder).fetchSingle(long)

        override val transaction: RealTransaction?
            get() = this@SqliteSession.transaction

        @Suppress("UPPER_BOUND_VIOLATED")
        private val localReusableCond = ThreadLocal<ColCond<Any, Any?>>()

        @Suppress("UNCHECKED_CAST")
        override fun <SCH : Schema<SCH>, ID : IdBound> pkCond(
                table: Table<SCH, ID, out Record<SCH, ID>>, value: ID
        ): ColCond<SCH, ID> {
            val condition = (localReusableCond as ThreadLocal<ColCond<SCH, ID>>).getOrSet {
                ColCond(table.pkColumn as Lens<SCH, Record<SCH, *>, ID, *>, " = ?", value)
            }
            condition.lens = table.pkColumn as Lens<SCH, Record<SCH, *>, ID, *> // unchecked: we don't mind actual types
            condition.valueOrValues = value
            return condition
        }

        private fun <T> Cursor.fetchAllRows(type: DataType<T>): List<T> {
            if (!moveToFirst()) {
                close()
                return emptyList()
            }

            val values = ArrayList<Any?>()
            do {
                values.add(type.get(this, 0))
            } while (moveToNext())
            close()
            return values as List<T>
        }

        private fun <T> Cursor.fetchSingle(type: DataType<T>): T =
                try {
                    check(moveToFirst())
                    type.get(this, 0)
                } finally {
                    close()
                }

        private fun <SCH : Schema<SCH>> Cursor.fetchColumns(columns: Array<out StoredNamedLens<SCH, *, *>>): Array<Any?> =
                try {
                    check(moveToFirst())
                    columns.mapIndexedToArray { index, column ->
                        column.type.get(this, index)
                    }
                } finally {
                    close()
                }

        internal fun <T> DataType<T>.bind(statement: SQLiteProgram, index: Int, value: T) {
            val i = 1 + index
            flattened { isNullable, simple ->
                if (value == null) {
                    check(isNullable)
                    statement.bindNull(i)
                } else {
                    val v = simple.store(value)
                    when (simple.kind) {
                        DataType.Simple.Kind.Bool -> statement.bindLong(i, if (v as Boolean) 1 else 0)
                        DataType.Simple.Kind.I8,
                        DataType.Simple.Kind.I16,
                        DataType.Simple.Kind.I32,
                        DataType.Simple.Kind.I64 -> statement.bindLong(i, (v as Number).toLong())
                        DataType.Simple.Kind.F32,
                        DataType.Simple.Kind.F64 -> statement.bindDouble(i, (v as Number).toDouble())
                        DataType.Simple.Kind.Str -> statement.bindString(i, v as String)
                        DataType.Simple.Kind.Blob -> statement.bindBlob(i, v as ByteArray)
                    }.also { }
                }
            }
        }

        @Suppress("IMPLICIT_CAST_TO_ANY", "UNCHECKED_CAST")
        private fun <T> DataType<T>.get(cursor: Cursor, index: Int): T = flattened { isNullable, simple ->
            if (cursor.isNull(index))
                check(isNullable).let { null as T }
            else simple.load(when (simple.kind) {
                DataType.Simple.Kind.Bool -> cursor.getInt(index) == 1
                DataType.Simple.Kind.I8 -> cursor.getShort(index).assertFitsByte()
                DataType.Simple.Kind.I16 -> cursor.getShort(index)
                DataType.Simple.Kind.I32 -> cursor.getInt(index)
                DataType.Simple.Kind.I64 -> cursor.getLong(index)
                DataType.Simple.Kind.F32 -> cursor.getFloat(index)
                DataType.Simple.Kind.F64 -> cursor.getDouble(index)
                DataType.Simple.Kind.Str -> cursor.getString(index)
                DataType.Simple.Kind.Blob -> cursor.getBlob(index)
            })
        }

        private fun Short.assertFitsByte(): Byte {
            require(this in Byte.MIN_VALUE..Byte.MAX_VALUE) {
                "value $this cannot be fit into ${Byte::class.java.simpleName}"
            }
            return toByte()
        }

    }


    override fun beginTransaction(): Transaction {
        val wLock = lock.writeLock()
        check(!wLock.isHeldByCurrentThread) { "Thread ${Thread.currentThread()} is already in a transaction" }
        wLock.lock()
        connection.beginTransaction()
        val tr = RealTransaction(this, lowLevel)
        transaction = tr
        return tr
    }

    // endregion transactions and modifying statements

    override fun toString(): String =
            "SqliteSession(connection=$connection)"


    fun dump(sb: StringBuilder) {
        sb.append("DAOs\n")
        lowLevel.daos.forEach { (table: Table<*, *, *>, dao: Dao<*, *, *>) ->
            sb.append(" ").append(table.name).append("\n")
            dao.dump("  ", sb)

            sb.append("  select statements (for current thread)\n")
            dao.selectStatements.get()?.keys?.forEach { sql ->
                sb.append(' ').append(sql).append("\n")
            }

            arrayOf(
                    "insert statements" to dao.insertStatement,
                    "update statements" to dao.updateStatements,
                    "delete statements" to dao.deleteStatement
            ).forEach { (text, stmts) ->
                sb.append("  ").append(text).append(": ").append(stmts)
            }
        }
    }

}

/**
 * Calls [SQLiteDatabase.execSQL] for the given [table] in [this] database.
 */
fun SQLiteDatabase.createTable(table: Table<*, *, *>) {
    execSQL(SqliteDialect.createTable(table))
}