package com.example.birdingsoundmvp.audio

/** Calls are on the main thread, matching both Android players. */
object PlaybackCoordinator {
    private var stopCurrent: (() -> Unit)? = null
    private var currentOwner: Any? = null
    var recording: Boolean = false
        set(value) { field = value; if (value) stop() }

    fun acquire(owner: Any, stop: () -> Unit): Boolean {
        if (recording) return false
        if (currentOwner === owner) return true
        stopCurrent?.invoke()
        stopCurrent = stop
        currentOwner = owner
        return true
    }
    fun release(owner: Any) {
        if (currentOwner === owner) { currentOwner = null; stopCurrent = null }
    }
    fun stop() {
        val callback = stopCurrent
        stopCurrent = null
        currentOwner = null
        callback?.invoke()
    }
}
