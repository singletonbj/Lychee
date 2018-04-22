package net.aquadc.properties.internal

import net.aquadc.properties.ChangeListener
import net.aquadc.properties.Property

@PublishedApi
internal class DistinctPropertyWrapper<out T>(
        private val original: Property<T>,
        private val areEqual: (T, T) -> Boolean
) : PropNotifier<T>(threadIfNot(original.isConcurrent)), ChangeListener<@UnsafeVariance T> {

    init {
        check(original.mayChange)
    }

    override val value: T
        get() {
            if (thread != null) checkThread()
            return original.value
        }

    override fun invoke(old: @UnsafeVariance T, new: @UnsafeVariance T) {
        if (!areEqual(old, new)) {
            valueChanged(old, new, null)
        }
    }

    override fun observedStateChangedWLocked(observed: Boolean) {
        if (observed) {
            original.addChangeListener(this)
        } else {
            original.removeChangeListener(this)
        }
    }

}