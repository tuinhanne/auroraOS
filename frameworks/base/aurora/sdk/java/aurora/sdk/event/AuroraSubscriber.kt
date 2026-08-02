/*
 * Copyright (C) 2026 The AuroraOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package aurora.sdk.event

/**
 * Receives events of one type.
 *
 * A `fun interface`, so a lambda works wherever one is expected.
 */
fun interface AuroraSubscriber<T : AuroraEvent> {

    /**
     * Handles one event.
     *
     * Throwing here does not stop the remaining subscribers: the bus isolates each one, so a
     * fault in a logging subscriber cannot silently prevent a state update from being
     * delivered.
     */
    fun onEvent(event: T)
}

/**
 * A handle to something that can be released.
 *
 * ## Why the subscription returns this instead of an id to pass back
 *
 * An `unsubscribe(id)` call puts the burden on the caller to keep the id and to reach the bus
 * again at teardown time. Both get forgotten, and a forgotten subscription is a leak that also
 * keeps firing.
 *
 * A handle keeps the two halves together: whatever holds the subscription holds the only thing
 * needed to end it. It also composes — see [CompositeDisposable] — so a component with several
 * subscriptions still has exactly one thing to release.
 */
interface Disposable {

    /**
     * Releases the resource.
     *
     * Idempotent. Calling it twice is not an error, because teardown paths run more than once
     * more often than anyone expects, and a throwing second call turns a harmless duplicate
     * into a crash.
     */
    fun dispose()

    /** Whether [dispose] has been called. */
    val isDisposed: Boolean
}

/**
 * A [Disposable] that owns several others.
 *
 * Lets a component with many subscriptions expose one thing to release. Disposing the
 * composite disposes everything it holds; anything added afterwards is disposed immediately,
 * so a late registration during teardown cannot outlive the owner.
 */
class CompositeDisposable : Disposable {

    private val lock = Any()
    private val children = mutableListOf<Disposable>()

    @Volatile
    private var disposed = false

    override val isDisposed: Boolean
        get() = disposed

    /**
     * Takes ownership of [disposable].
     *
     * If this composite is already disposed, [disposable] is disposed at once rather than
     * being stored, so nothing registered during teardown survives it.
     */
    fun add(disposable: Disposable) {
        synchronized(lock) {
            if (!disposed) {
                children.add(disposable)
                return
            }
        }
        disposable.dispose()
    }

    /** Number of live children. Intended for tests. */
    val size: Int
        get() = synchronized(lock) { children.size }

    override fun dispose() {
        val toDispose: List<Disposable>
        synchronized(lock) {
            if (disposed) return
            disposed = true
            toDispose = children.toList()
            children.clear()
        }
        // Outside the lock: a child's dispose may re-enter this object.
        toDispose.forEach { it.dispose() }
    }
}
