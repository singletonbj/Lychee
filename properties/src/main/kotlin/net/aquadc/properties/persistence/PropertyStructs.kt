@file:JvmName("PropertyStructs")
package net.aquadc.properties.persistence

import net.aquadc.persistence.array
import net.aquadc.persistence.struct.*
import net.aquadc.properties.Property
import net.aquadc.properties.TransactionalProperty
import net.aquadc.properties.mapValueList

/**
 * A struct which has observable properties.
 */
interface PropertyStruct<SCH : Schema<SCH>> : Struct<SCH> {

    /**
     * @return a property representing a given [field]
     */
    infix fun <T> prop(field: FieldDef.Mutable<SCH, T, *>): Property<T>

}

/**
 * A struct which has observable transactional properties.
 */
interface TransactionalPropertyStruct<SCH : Schema<SCH>> : PropertyStruct<SCH>, TransactionalStruct<SCH> {

    /**
     * @return a property representing a given [field]
     */
    override fun <T> prop(field: FieldDef.Mutable<SCH, T, *>): TransactionalProperty<StructTransaction<SCH>, T>

}


/**
 * Returns a [Property] containing snapshots of [this] mutable struct.
 */
fun <SCH : Schema<SCH>> PropertyStruct<SCH>.snapshots(): Property<Struct<SCH>> {
    val fields = schema.fields
    return schema.mutableFields.map { prop(it) }.mapValueList { newMutableValues ->
        StructSnapshot(schema, if (schema.immutableFields.isEmpty()) newMutableValues.array() else {
            Array(fields.size) { i ->
                when (val field = fields[i]) {
                    is FieldDef.Mutable -> newMutableValues[field.mutableOrdinal.toInt()]
                    is FieldDef.Immutable -> this@snapshots[field]
                }
            }
        })
    }
}

/**
 * Creates a property getter, i. e. a function which returns a property of a pre-set [field] of a given [SCH].
 */
fun <SCH : Schema<SCH>, T> propertyGetterOf(field: FieldDef.Mutable<SCH, T, *>): (PropertyStruct<SCH>) -> Property<T> =
        { it prop field }
