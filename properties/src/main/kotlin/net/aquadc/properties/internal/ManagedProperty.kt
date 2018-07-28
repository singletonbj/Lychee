package net.aquadc.properties.internal

import net.aquadc.properties.MutableProperty
import net.aquadc.properties.Property

/**
 * A property whose value can be changed inside a transaction.
 */
@PublishedApi internal class ManagedProperty<T, TOKN>(
        private val manager: Manager<TOKN, T>,
        private val token: TOKN,
        private val id: Long
) : `Notifier+1AtomicRef`<T, T>(true, unset()), MutableProperty<T>, (@ParameterName("newValue") T) -> Unit {

    override var value: T
        get() {
            // check for uncommitted changes
            val dirty = manager.getDirty(token, id)
            if (dirty !== Unset) return dirty

            // check cached
            val cached = ref
            if (cached !== Unset) return cached

            val clean = manager.getClean(token, id)
            refUpdater().lazySet(this, clean)
            return clean
        }
        set(value) {
            check(casValue(Unset as T, value))
        }

    /**
     * This doesn't work for such properties for consistency reasons:
     * (1) normal concurrent in-memory property can be mutated from any thread, it's not a problem,
     *     but when the property is bound to a database field, it can be mutated only in a transaction,
     *     making such binding dangerous and unpredictable;
     * (2) transactions should be short, and property binding looks like a long-term thing.
     */
    override fun bindTo(sample: Property<T>): Nothing {
        throw UnsupportedOperationException("This is possible to implement but looks very questionable.")
    }

    override fun casValue(expect: T, update: T): Boolean {
        val clean = if (ref === Unset) manager.getClean(token, id) else Unset
        // after mutating dirty state we won't be able to see the clean one, so preserve it

        val success = manager.set(token, id, expect, value, this)
        // this changes 'dirty' state (and value returned by 'get'),
        // but we don't want to deliver it until it becomes clean

        if (clean !== Unset) ref = clean as T // mutated successfully, preserve clean

        return success
    }

    override fun invoke(newValue: T) {
        if (newValue !== Unset) {
            // the transaction was committed

            val oldValue = ref
            check(oldValue !== Unset) // we've saved it earlier
            ref = newValue

            valueChanged(oldValue, newValue, null)
        }
    }

}

interface Manager<TOKN, T> {

    /**
     * Returns dirty transaction value for current thread, or [Unset], if none.
     */
    fun getDirty(token: TOKN, id: Long): T

    /**
     * Returns clean value.
     */
    fun getClean(token: TOKN, id: Long): T

    /**
     * Set, if [expected] === [Unset]; CAS otherwise.
     * @param onTransactionEnd will be invoked after transaction end;
     *   param newValue: new value, if transaction was committed, or [Unset], if it was rolled back
     * @return if write was successful; simple sets are always successful.
     */
    fun set(token: TOKN, id: Long, expected: Any?, update: T, onTransactionEnd: (newValue: T) -> Unit): Boolean
}

inline fun <T, TOKN> newManagedProperty(manager: Manager<TOKN, T>, token: TOKN, id: Long): MutableProperty<T> =
        ManagedProperty(manager, token, id)
