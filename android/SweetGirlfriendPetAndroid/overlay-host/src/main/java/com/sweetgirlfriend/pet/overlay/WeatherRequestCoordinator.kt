package com.sweetgirlfriend.pet.overlay

/**
 * Serializes weather requests without depending on Android threading primitives.
 *
 * A request for the same query always joins the in-flight network request before considering a
 * cached value. Switching query invalidates completion of older requests, so stale cities cannot
 * overwrite the active cache.
 */
internal class WeatherRequestCoordinator<T> {
    internal class Pending<T>(
        val query: String,
        internal val callbacks: MutableList<(Result<T>) -> Unit>,
    )

    internal sealed interface Decision<T> {
        data class Start<T>(val request: Pending<T>) : Decision<T>
        data class Joined<T>(val request: Pending<T>) : Decision<T>
        data class Cached<T>(val value: T, val callback: (Result<T>) -> Unit) : Decision<T>
    }

    internal data class Completion<T, R>(
        val callbacks: List<(Result<T>) -> Unit>,
        val value: R,
    )

    private val lock = Any()
    private val inFlight = mutableMapOf<String, Pending<T>>()
    private var latestQuery = ""

    fun activate(query: String) = synchronized(lock) {
        latestQuery = query
    }

    fun begin(
        query: String,
        cached: T?,
        allowCached: Boolean,
        callback: (Result<T>) -> Unit,
    ): Decision<T> = synchronized(lock) {
        latestQuery = query
        inFlight[query]?.let { request ->
            request.callbacks += callback
            return@synchronized Decision.Joined(request)
        }
        if (allowCached && cached != null) {
            return@synchronized Decision.Cached(cached, callback)
        }
        val request = Pending(query, mutableListOf(callback))
        inFlight[query] = request
        Decision.Start(request)
    }

    /** Runs [onCurrent] while query activation is locked, making cache writes race-free. */
    fun <R> completeIfCurrent(request: Pending<T>, onCurrent: () -> R): Completion<T, R>? =
        synchronized(lock) {
            if (inFlight[request.query] !== request) return@synchronized null
            inFlight.remove(request.query)
            if (latestQuery != request.query) return@synchronized null
            Completion(request.callbacks.toList(), onCurrent())
        }

    fun isCurrent(query: String): Boolean = synchronized(lock) { latestQuery == query }
}
