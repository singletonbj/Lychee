@file:JvmName("BasicTypes")
package net.aquadc.persistence.type

import net.aquadc.persistence.fatAsList
import net.aquadc.persistence.fatTo
import java.util.EnumSet


private class SimpleNoOp<T>(kind: Kind) : DataType.Simple<T>(kind) {

    @Suppress("UNCHECKED_CAST")
    override fun load(value: SimpleValue): T {
        sanityCheck(value)
        return value as T
    }

    override fun store(value: T): SimpleValue {
        sanityCheck(value)
        return value!!
    }

    // note: this debug assertion helps finding bad types in tests but will be removed by ProGuard in production.
    // good (de)serializers must never rely on this check and pass already cast* values
    // *cast is an irregular verb: cast | cast | cast
    private fun sanityCheck(value: Any?) {
        when (kind) {
            Kind.Bool -> value as Boolean
            Kind.I8 -> value as Byte
            Kind.I16 -> value as Short
            Kind.I32 -> value as Int
            Kind.I64 -> value as Long
            Kind.F32 -> value as Float
            Kind.F64 -> value as Double
            Kind.Str -> value as String
            Kind.Blob -> value as ByteArray
        }
    }

}

/**
 * Describes [Boolean] instances.
 */
@JvmField val bool: DataType.Simple<Boolean> = SimpleNoOp(DataType.Simple.Kind.Bool)

/**
 * Describes [Byte] instances.
 */
@JvmField @Deprecated("does not look very useful")
val i8: DataType.Simple<Byte> = SimpleNoOp(DataType.Simple.Kind.I8)
@JvmField @Deprecated("renamed", ReplaceWith("i8")) val byte = i8

/**
 * Describes [Short] instances.
 */
@JvmField @Deprecated("does not look very useful")
val i16: DataType.Simple<Short> = SimpleNoOp(DataType.Simple.Kind.I16)
@JvmField @Deprecated("renamed", ReplaceWith("i16")) val short = i16

/**
 * Describes [Int] instances.
 */
@JvmField val i32: DataType.Simple<Int> = SimpleNoOp(DataType.Simple.Kind.I32)
@JvmField @Deprecated("renamed", ReplaceWith("i32")) val int = i32

/**
 * Describes [Long] instances.
 */
@JvmField val i64: DataType.Simple<Long> = SimpleNoOp(DataType.Simple.Kind.I64)
@JvmField @Deprecated("renamed", ReplaceWith("i64")) val long = i64

/**
 * Describes [Float] instances.
 */
@JvmField val f32: DataType.Simple<Float> = SimpleNoOp(DataType.Simple.Kind.F32)
@JvmField @Deprecated("renamed", ReplaceWith("f32")) val float = f32

/**
 * Describes [Double] instances.
 */
@JvmField val f64: DataType.Simple<Double> = SimpleNoOp(DataType.Simple.Kind.F64)
@JvmField @Deprecated("renamed", ReplaceWith("f64")) val double = f64

/**
 * Describes [String] instances.
 */
@JvmField val string: DataType.Simple<String> = SimpleNoOp(DataType.Simple.Kind.Str)

/**
 * Describes [ByteArray] instances.
 * Despite this declaration is deprecated, it is not going to disappear,
 * and [Deprecated.level] will remain [DeprecationLevel.WARNING].
 */
@Deprecated(
        "Note: if you mutate array, we won't notice — you must set() it in a transaction. " +
                "Consider using immutable ByteString instead.",
        ReplaceWith("byteString")
)
@JvmField val byteArray: DataType.Simple<ByteArray> = SimpleNoOp(DataType.Simple.Kind.Blob)

/**
 * Describes `T?` instances.
 */
@Suppress("NOTHING_TO_INLINE")
inline fun <T : Any, DT : DataType<T>> nullable(type: DT): DataType.Nullable<T, DT> =
        DataType.Nullable(type)

internal abstract class CollectBase<C : Collection<E>, E, DE : DataType<E>>(elementType: DE)
    : DataType.Collect<C, E, DE>(elementType) {

    override fun store(value: C): AnyCollection =
            value

}

/**
 * Represents a [Collection] of [E].
 * Despite it is represented as a [List], duplicates handling depends on the underlying storage.
 */
fun <E, DE : DataType<E>> collection(elementType: DE): DataType.Collect<List<E>, E, DE> =
        object : CollectBase<List<E>, E, DE>(elementType) {
            override fun load(value: AnyCollection): List<E> =
                    value.fatAsList() as List<E> // almost always zero copy
        }

/**
 * Represents a [Set] of [E].
 */
fun <E, DE : DataType<E>> set(elementType: DE): DataType.Collect<Set<E>, E, DE> =
        setInternal(elementType, null)

@PublishedApi internal fun <E, DE : DataType<E>> setInternal(elementType: DE, enumType: Class<E>?): CollectBase<Set<E>, E, DE> {
    return object : CollectBase<Set<E>, E, DE>(elementType) {
        override fun load(value: AnyCollection): Set<E> =
                if (value is Set<*>) value as Set<E>
                else value.fatTo(
                        if (enumType === null) HashSet()
                        else (EnumSet.noneOf(enumType as Class<Thread.State>) as MutableSet<E>)
                )
    }
}

/**
 * A hint which makes [collection] easier to find.
 * Despite this declaration is deprecated, it is not going to be removed.
 */
@Deprecated(
        "List semantics cannot be guaranteed: duplicates handling depends on the underlying storage",
        ReplaceWith("collection(elementType", "net.aquadc.persistence.type.collection"),
        DeprecationLevel.ERROR
)
fun <E> list(@Suppress("UNUSED_PARAMETER") elementType: DataType<E>): Nothing =
        throw UnsupportedOperationException()

@JvmField val nothing: DataType.Simple<Nothing> = object : DataType.Simple<Nothing>(Kind.I32) {
    override fun load(value: SimpleValue): Nothing =
            throw UnsupportedOperationException()

    override fun store(value: Nothing): SimpleValue =
            throw UnsupportedOperationException()
}
