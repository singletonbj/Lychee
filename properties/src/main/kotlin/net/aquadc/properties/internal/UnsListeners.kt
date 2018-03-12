package net.aquadc.properties.internal

import net.aquadc.properties.ChangeListener
import net.aquadc.properties.Property
import java.util.*
import kotlin.collections.ArrayList

/**
 * Base class for unsynchronzed properties.
 * I don't like implementation inheritance, but it is more lightweight than composition.
 */
abstract class UnsListeners<out T> : Property<T> {

    private val thread = Thread.currentThread()

    final override val mayChange: Boolean
        get() {
            checkThread()
            return true
        }

    final override val isConcurrent: Boolean
        get() {
            checkThread()
            return false
        }

    private var listeners: ArrayList<ChangeListener<T>?>? = null
    @Suppress("UNCHECKED_CAST")
    final override fun addChangeListener(onChange: ChangeListener<T>) {
        checkThread()

        val liss = listeners
        if (liss == null) listeners = ArrayList<ChangeListener<T>?>(4).also { it.add(onChange) }
        else liss.add(onChange)
    }

    @Suppress("UNCHECKED_CAST")
    final override fun removeChangeListener(onChange: ChangeListener<T>) {
        checkThread()
        val liss = listeners ?: return

        val idx = liss.indexOf(onChange)
        if (idx < 0) return
        if (notifying) liss[idx] = null
        else liss.removeAt(idx)
    }

    private var notifying = false
    private var pendingValues: Queue<T>? = null

    /**
     * It's a tricky one.
     * First, while notifying, can't remove listeners from ArrayList
     * because this will break indices; may null out them instead.
     * Second, can't update value while notifying: this will lead to stack overflow.
     * Must persist all pending values and deliver them later.
     */
    protected fun valueChanged(old: /*T*/ Any?, new: /*T*/ Any?) {
        // if we have no one to notify, just give up
        if (listeners == null) return

        if (notifying) {
            // This method is already on the stack, somewhere deeper.
            // Just push the new value and let deeper version of this method pick it up.
            var pending = pendingValues
            if (pending == null) {
                pending = LinkedList()
                pendingValues = pending
            }

            pending.add(new as T)
            return
        }

        notifying = true
        notify(old, new)
        notifying = false

        pendingValues?.let {
            var older = new
            while (!it.isEmpty()) {
                val newer = it.remove()

                notifying = true
                notify(older, newer)
                notifying = false

                older = newer
            }
        }

        // clean up nulled out listeners
        listeners?.let {
            var idxOf0 = it.indexOf(null)
            while (idxOf0 >= 0) {
                it.removeAt(idxOf0)
                idxOf0 = it.indexOf(null)
            }
        }
    }

    private fun notify(old: Any?, new: Any?) {
        old as T; new as T
        val listeners = listeners ?: return

        var i = 0
        // size may change, so we can't use Kotlin's for loop here
        while (i < listeners.size) {
            listeners[i]?.invoke(old, new)
            i++
        }
    }

    protected fun checkThread() {
        if (Thread.currentThread() !== thread)
            throw RuntimeException("${Thread.currentThread()} is not allowed to touch this property since it was created in $thread.")
    }

}