package net.aquadc.persistence.type

import net.aquadc.persistence.struct.FieldDef
import net.aquadc.persistence.struct.FieldSet
import net.aquadc.persistence.struct.Schema
import net.aquadc.persistence.struct.Struct


/**
 * Used by [DataType.Simple] and represents the following type, according to [DataType.Simple.Kind]:
 * [Boolean] | [Byte] | [Short] | [Int] | [Long] | [Float] | [Double] | [String] | [ByteArray]
 */
typealias SimpleValue = Any

/**
 * Used by [DataType.Collect] and represents the following type:
 * [Collection]<E> | [Array]<E> | EArray
 * where E represents [Byte], [Short], [Int], [Long], [Float], [Double],
 * EArray means [ByteArray], [ShortArray], [IntArray], [LongArray], [FloatArray], [DoubleArray] accordingly
 */
typealias AnyCollection = Any
// @see fatMap, fatMapTo, fatAsList, don't forget to update them

typealias SimpleNullable<T> = DataType.Nullable<T, DataType.Simple<T>>

/**
 * Describes type of stored values and underlying serialization techniques.
 * This property is a part of serialization ABI.
 *
 * Replacing one DataType<T> with another DataType<T> (which is OK for source and binary compatibility)
 * may break serialization compatibility,
 * while replacing DataType<T1> with DataType<T2> (which may break source and binary compatibility)
 * may not, and vice versa.
 *
 * Data types are compatible if
 * * d1 is [DataType.Simple] and d2 is [DataType.Simple] and `d1.kind == d2.kind`
 * * d1 is [DataType.Collect] and d2 is [DataType.Collect] and d1.elementType is compatible with d2.elementType
 * * d1 is [DataType.Partial] and d2 is [DataType.Partial] and d1.schema is compatible to d2.schema
 *   (schemas s1 and s2 are considered to be compatible when they have the same number of fields,
 *    for each n s1.fields[n] has type compatible to s2.fields[n],
 *    and, depending on underlying serialization machinery,
 *    s1.fields[n] has either same name or same ordinal as s2.fields[n].)
 */
sealed class DataType<T> {

    /**
     * Adds nullability to runtime representation of [actualType].
     * Wraps only non-nullable type, represented as `null` in memory.
     * (However, some non-standard [Simple], [Collect], or [Partial] implementations
     * may have nullable in-memory representation, and thus cannot be wrapped into [Nullable])
     */
    class Nullable<T : Any, DT : DataType<T>>(
            /**
             * Wrapped non-nullable type.
             */
            @JvmField val actualType: DT
    ) : DataType<T?>() {

        init {
            if (actualType is Nullable<*, *>) throw ClassCastException() // unchecked cast?..
        }

        override fun hashCode(): Int =
                actualType.hashCode() xor 0x55555555

        // looks useless but helps using assertEquals() in tests

        override fun equals(other: Any?): Boolean =
                other is Nullable<*, *> && actualType == other.actualType

    }

    /**
     * A simple, non-composite (and thus easily composable) type.
     */
    abstract class Simple<T>(
            /**
             * Specifies exact type of stored values.
             */
            @JvmField val kind: Kind
    ) : DataType<T>() {

        enum class Kind {
            Bool,
            @Deprecated("does not look very useful") I8,
            @Deprecated("does not look very useful") I16,
            I32, I64,
            F32, F64,
            Str, Blob,
        }

        /**
         * Converts a simple persistable value into its in-memory representation.
         * @return in-memory representation of [value]
         */
        abstract fun load(value: SimpleValue): T

        /**
         * Converts in-memory value into its simple persistable representation.
         * @return persistable representation of [value]
         */
        abstract fun store(value: T): SimpleValue

        // TODO: to & from String, e. g. to encode UUID as blob, but use string representation in JSON

    }

    /**
     * A collection of elements of [elementType].
     * In-memory type [C] is typically a collection, but it is not required.
     * May have [List] or [Set] semantics, depending on implementations
     * of both this data type and the underlying storage.
     *
     * Collection DataType handles only converting from/to a specified collection type,
     * leaving values untouched.
     */
    abstract class Collect<C, E, DE : DataType<E>>(
            /**
             * [DataType] of all the elements in such collections.
             */
            @JvmField val elementType: DE
    ) : DataType<C>() {

        /**
         * Converts a persistable collection value into its in-memory representation.
         * Elements of input collection are already in their in-memory representation
         * @return in-memory representation of [value]
         */
        abstract fun load(value: AnyCollection): C

        /**
         * Converts in-memory value into a persistable collection.
         * Values of output collection must be in their in-memory representation,
         * it's caller's responsibility to convert them to persistable representation.
         * @return persistable representation of [value], a collection of in-memory representations
         */
        abstract fun store(value: C): AnyCollection

    }

    /**
     * Represents a set of optional key-value mappings, according to [schema].
     * [Schema] itself represents a special case of [Partial], where all mappings are required.
     */
    abstract class Partial<T, SCH : Schema<SCH>> : DataType<T> {

        @JvmField val schema: SCH
        
        constructor(schema: SCH) {
            this.schema = schema
        }
        
        internal constructor() { // for Schema itself
            this.schema = this as SCH
        }
        
        /**
         * Converts a persistable value into its in-memory representation.
         * @param fields a set of fields provided within [values] array
         * @param values values in their in-memory representation according to [fields] size
         *   0 -> ignored
         *   1 -> the value for the only field
         *   else -> 'packed' layout, no gaps between values
         * @return in-memory representation of [fields] and their [values]
         * @see net.aquadc.persistence.struct.indexOf
         * @see net.aquadc.persistence.fill
         */
        abstract fun load(fields: FieldSet<SCH, FieldDef<SCH, *, *>>, values: Any?): T

        /**
         * Returns a set of fields which have values.
         * Required to parse data returned by [store] function.
         */
        abstract fun fields(value: T): FieldSet<SCH, FieldDef<SCH, *, *>>

        /**
         * Converts in-memory value into its persistable representation.
         * @param value an input value to read from
         * @return all values, using the same layouts as in [load], in their unchanged, in-memory representation
         * @see fields to know how to interpret the return value
         */
        abstract fun store(value: T): Any?

    }


    override fun equals(other: Any?): Boolean {
        if (other !is DataType<*> || javaClass !== other.javaClass) return false
        // class identity equality   ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ guarantees the same behaviour

        return when (this) {
            is Nullable<*, *> -> other is Nullable<*, *> && actualType as DataType<*> == other.actualType
            is Simple -> other is Simple<*> && kind === other.kind
            is Collect<*, *, *> -> other is Collect<*, *, *> && elementType == other.elementType
            is Partial<*, *> -> other is Partial<*, *> && schema == other.schema
        }
    }

    override fun hashCode(): Int = when (this) {
        is Nullable<*, *> -> 13 * actualType.hashCode()
        is Simple -> 31 * kind.hashCode()
        is Collect<*, *, *> -> 63 * elementType.hashCode()
        is Partial<*, *> -> (if (this is Struct<*>) 1 else 127) * schema.hashCode()
    }

    override fun toString(): String = when (this) {
        is Nullable<*, *> -> "nullable($actualType)"
        is Simple -> kind.toString()
        is Collect<*, *, *> -> "collection($elementType)"
        is Partial<*, *> -> "partial($schema)" // overridden in Schema itself
    }


//    abstract class Dictionary<M, K, V> internal constructor(keyType: DataType<K>, valueType: DataType<K>) : DataType<M>(isNullable) TODO

}

/**
 * Match/visit (on) this [dataType], passing [arg] and [payload] into [this] visitor.
 */
@Suppress(
        "NOTHING_TO_INLINE", // hope this will generate monomorphic call-site
        "UNCHECKED_CAST"
)
inline fun <PL, ARG, T, R> DataTypeVisitor<PL, ARG, T, R>.match(dataType: DataType<T>, payload: PL, arg: ARG): R =
    when (dataType) {
        is DataType.Nullable<*, *> -> {
            when (val actualType = dataType.actualType as DataType<T/*!!*/>) {
                is DataType.Nullable<*, *> -> throw AssertionError()
                is DataType.Simple -> payload.simple(arg, true, actualType)
                is DataType.Collect<*, *, *> -> payload.collection(arg, true, actualType as DataType.Collect<T, Any?, DataType<Any?>>)
                is DataType.Partial<T, *> -> @Suppress("UPPER_BOUND_VIOLATED")
                        payload.partial<Schema<*>>(arg, true, actualType as DataType.Partial<T, Schema<*>>)
            }
        }
        is DataType.Simple -> payload.simple(arg, false, dataType)
        is DataType.Collect<T, *, *> -> payload.collection(arg, false, dataType as DataType.Collect<T, Any, out DataType<Any>>)
        is DataType.Partial<T, *> -> @Suppress("UPPER_BOUND_VIOLATED")
                payload.partial<Schema<*>>(arg, false, dataType as DataType.Partial<T, Schema<*>>)
    }

interface DataTypeVisitor<PL, ARG, T, R> {
    fun PL.simple(arg: ARG, nullable: Boolean, type: DataType.Simple<T>): R
    fun <E> PL.collection(arg: ARG, nullable: Boolean, type: DataType.Collect<T, E, out DataType<E>>): R
    fun <SCH : Schema<SCH>> PL.partial(arg: ARG, nullable: Boolean, type: DataType.Partial<T, SCH>): R
}
