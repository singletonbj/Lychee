package net.aquadc.properties.sql

import net.aquadc.persistence.struct.FieldDef
import net.aquadc.persistence.struct.Schema
import net.aquadc.persistence.struct.Struct
import net.aquadc.persistence.type.DataType
import net.aquadc.properties.sql.dialect.Dialect
import net.aquadc.persistence.type.long
import net.aquadc.persistence.type.match
import java.sql.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.concurrent.getOrSet

/**
 * Represents a database connection through JDBC.
 */
class JdbcSession(
        private val connection: Connection,
        private val dialect: Dialect
) : Session {

    init {
        connection.autoCommit = false
    }

    private val lock = ReentrantReadWriteLock()

    @Suppress("UNCHECKED_CAST")
    override fun <SCH : Schema<SCH>, ID : IdBound, REC : Record<SCH, ID>> get(
            table: Table<SCH, ID, REC>
    ): Dao<SCH, ID, REC> =
            lowLevel.daos.getOrPut(table) { RealDao(this, lowLevel, table, dialect) } as Dao<SCH, ID, REC>

    // region transactions and modifying statements

    // transactional things, guarded by write-lock
    private var transaction: RealTransaction? = null
    private val selectStatements = ThreadLocal<HashMap<String, PreparedStatement>>()
    private val replaceStatements = HashMap<Table<*, *, *>, PreparedStatement>()
    private val updateStatements = HashMap<Pair<Table<*, *, *>, FieldDef<*, *>>, PreparedStatement>()
    private val deleteStatements = HashMap<Table<*, *, *>, PreparedStatement>()

    private val lowLevel = object : LowLevelSession {

        override fun <SCH : Schema<SCH>, ID : IdBound> exists(table: Table<SCH, ID, *>, primaryKey: ID): Boolean {
            val count = select(null, table, reusableCond(table, table.idColName, primaryKey), NoOrder).fetchSingle(long)
            return when (count) {
                0L -> false
                1L -> true
                else -> throw AssertionError()
            }
        }

        private fun <SCH : Schema<SCH>> insertStatementWLocked(table: Table<SCH, *, *>): PreparedStatement =
                replaceStatements.getOrPut(table) {
                    connection.prepareStatement(dialect.replace(table, table.schema.fields), Statement.RETURN_GENERATED_KEYS)
                }

        override fun <SCH : Schema<SCH>, ID : IdBound> replace(table: Table<SCH, ID, *>, data: Struct<SCH>): ID {
            val statement = insertStatementWLocked(table)
            val fields = table.schema.fields
            for (i in fields.indices) {
                val field = fields[i]
                field.type.erased.bind(statement, i, data[field])
            }
            check(statement.executeUpdate() == 1)
            val keys = statement.generatedKeys
            return keys.fetchSingle(table.idColType)
        }

        private fun <SCH : Schema<SCH>> updateStatementWLocked(table: Table<SCH, *, *>, col: FieldDef<SCH, *>): PreparedStatement =
                updateStatements.getOrPut(Pair(table, col)) {
                    connection.prepareStatement(dialect.updateFieldQuery(table, col))
                }

        override fun <SCH : Schema<SCH>, ID : IdBound, T> update(table: Table<SCH, ID, *>, id: ID, column: FieldDef<SCH, T>, value: T) {
            val statement = updateStatementWLocked(table, column)
            column.type.bind(statement, 0, value)
            table.idColType.bind(statement, 1, id)
            check(statement.executeUpdate() == 1)
        }

        private fun deleteStatementWLocked(table: Table<*, *, *>): PreparedStatement =
                deleteStatements.getOrPut(table) {
                    connection.prepareStatement(dialect.deleteRecordQuery(table))
                }

        override fun <ID : IdBound> delete(table: Table<*, ID, *>, primaryKey: ID) {
            val statement = deleteStatementWLocked(table)
            table.idColType.bind(statement, 0, primaryKey)
            check(statement.executeUpdate() == 1)
        }

        override fun truncate(table: Table<*, *, *>) {
            val stmt = connection.createStatement()
            try {
                stmt.execute(dialect.truncate(table))
            } finally {
                stmt.close()
            }
        }

        override val daos = ConcurrentHashMap<Table<*, *, *>, RealDao<*, *, *>>()

        override fun onTransactionEnd(successful: Boolean) {
            val transaction = transaction ?: throw AssertionError()
            try {
                if (successful) {
                    connection.commit()
                } else {
                    connection.rollback()
                }
                this@JdbcSession.transaction = null

                if (successful) {
                    transaction.deliverChanges()
                }
            } finally {
                lock.writeLock().unlock()
            }
        }

        private fun <ID : IdBound, SCH : Schema<SCH>> select(
                columnName: String?,
                table: Table<SCH, ID, *>,
                condition: WhereCondition<out SCH>,
                order: Array<out Order<out SCH>>
        ): ResultSet {
            val query =
                    if (columnName == null) dialect.selectCountQuery(table, condition)
                    else dialect.selectFieldQuery(columnName, table, condition, order)

            return selectStatements
                    .getOrSet(::HashMap)
                    .getOrPut(query) { connection.prepareStatement(query) }
                    .also { stmt ->
                        val argNames = ArrayList<String>()
                        val argValues = ArrayList<Any>()
                        condition.appendValuesTo(argNames, argValues)
                        forEachOfBoth(argNames, argValues) { idx, name, value ->
                            val conv =
                                    if (name == table.idColName) table.idColType
                                    else table.schema.fieldsByName[name]!!.type
                            conv.erased.bind(stmt, idx, value)
                        }
                    }
                    .executeQuery()
        }

        override fun <ID : IdBound, SCH : Schema<SCH>, T> fetchSingle(
                column: FieldDef<SCH, T>, table: Table<SCH, ID, *>, condition: WhereCondition<out SCH>
        ): T =
                select(column.name, table, condition, NoOrder).fetchSingle(column.type)

        override fun <ID : IdBound, SCH : Schema<SCH>> fetchPrimaryKeys(
                table: Table<SCH, ID, *>, condition: WhereCondition<out SCH>, order: Array<out Order<SCH>>
        ): Array<ID> =
                select(table.idColName, table, condition, order)
                        .fetchAll(table.idColType)
                        .toTypedArray<Any>() as Array<ID>

        private fun <T> ResultSet.fetchAll(type: DataType<T>): List<T> {
            val values = ArrayList<T>()
            while (next())
                values.add(type.get(this, 0))
            close()
            return values
        }

        override fun <ID : IdBound, SCH : Schema<SCH>> fetchCount(table: Table<SCH, ID, *>, condition: WhereCondition<out SCH>): Long =
                select(null, table, condition, NoOrder).fetchSingle(long)

        override val transaction: RealTransaction?
            get() = this@JdbcSession.transaction

        @Suppress("UPPER_BOUND_VIOLATED") private val localReusableCond = ThreadLocal<ColCond<Any, Any?>>()

        @Suppress("UNCHECKED_CAST")
        override fun <SCH : Schema<SCH>, T : Any> reusableCond(
                table: Table<SCH, *, *>, colName: String, value: T
        ): ColCond<SCH, T> {
            val condition = (localReusableCond as ThreadLocal<ColCond<SCH, T>>).getOrSet {
                ColCond(table.schema.fields[0] as FieldDef<SCH, T>, " = ?", value)
            }
            condition.colName = colName
            condition.valueOrValues = value
            return condition
        }

    }


    override fun beginTransaction(): Transaction {
        val wLock = lock.writeLock()
        check(!wLock.isHeldByCurrentThread) { "Thread ${Thread.currentThread()} is already in transaction" }
        wLock.lock()
        val tr = RealTransaction(this, lowLevel)
        transaction = tr
        return tr
    }

    // endregion transactions and modifying statements

    override fun toString(): String =
            "JdbcSession(connection=$connection, dialect=${dialect.javaClass.simpleName})"


    fun dump(sb: StringBuilder) {
        sb.append("DAOs\n")
        lowLevel.daos.forEach { (table: Table<*, *, *>, dao: Dao<*, *, *>) ->
            sb.append(" ").append(table.name).append("\n")
            dao.dump("  ", sb)
        }

        arrayOf(
                "select statements" to selectStatements
        ).forEach { (name, stmts) ->
            sb.append(name).append(" (for current thread)\n")
            stmts.get()?.keys?.forEach { sql ->
                sb.append(' ').append(sql).append("\n")
            }
        }

        arrayOf(
                "replace statements" to replaceStatements,
                "update statements" to updateStatements,
                "delete statements" to deleteStatements
        ).forEach { (text, stmts) ->
            sb.append(text).append('\n')
            stmts.keys.forEach {
                sb.append(' ').append(it).append('\n')
            }
        }
    }

    private fun <T> ResultSet.fetchSingle(type: DataType<T>): T {
        try {
            check(next())
            return type.get(this, 0)
        } finally {
            close()
        }
    }

    private fun <T> DataType<T>.bind(statement: PreparedStatement, index: Int, value: T) {
        val i = 1 + index
        match { isNullable, simple ->
            if (value == null) {
                check(isNullable)
                statement.setNull(i, Types.NULL)
            } else {
                val v = encode(value)
                when (simple.kind) {
                    DataType.Simple.Kind.Bool -> statement.setBoolean(i, v as Boolean)
                    DataType.Simple.Kind.I8 -> statement.setByte(i, v as Byte)
                    DataType.Simple.Kind.I16 -> statement.setShort(i, v as Short)
                    DataType.Simple.Kind.I32 -> statement.setInt(i, v as Int)
                    DataType.Simple.Kind.I64 -> statement.setLong(i, v as Long)
                    DataType.Simple.Kind.F32 -> statement.setFloat(i, v as Float)
                    DataType.Simple.Kind.F64 -> statement.setDouble(i, v as Double)
                    DataType.Simple.Kind.Str -> statement.setString(i, v as String)
                    // not sure whether setBlob should be used:
                    DataType.Simple.Kind.Blob -> statement.setObject(i, v as ByteArray)
                }.also { }
            }
        }
    }

    @Suppress("IMPLICIT_CAST_TO_ANY", "UNCHECKED_CAST")
    private fun <T> DataType<T>.get(resultSet: ResultSet, index: Int): T {
        val i = 1 + index

        return match { isNullable, simple ->
            val v = when (simple.kind) {
                DataType.Simple.Kind.Bool -> resultSet.getBoolean(i)
                DataType.Simple.Kind.I8 -> resultSet.getByte(i)
                DataType.Simple.Kind.I16 -> resultSet.getShort(i)
                DataType.Simple.Kind.I32 -> resultSet.getInt(i)
                DataType.Simple.Kind.I64 -> resultSet.getLong(i)
                DataType.Simple.Kind.F32 -> resultSet.getFloat(i)
                DataType.Simple.Kind.F64 -> resultSet.getDouble(i)
                DataType.Simple.Kind.Str -> resultSet.getString(i)
                DataType.Simple.Kind.Blob -> resultSet.getBytes(i)
            }

            // must check, will get zeroes otherwise
            if (resultSet.wasNull()) check(isNullable).let { null as T }
            else decode(v)
        }
    }

}
