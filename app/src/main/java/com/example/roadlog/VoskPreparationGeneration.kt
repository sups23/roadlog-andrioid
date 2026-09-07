package com.example.roadlog

/** Serializes model-preparation attempts and rejects callbacks from invalidated attempts. */
internal class VoskPreparationGeneration {
    private var current = 0L

    @Synchronized
    fun begin(): Long = ++current

    @Synchronized
    fun invalidate() {
        current++
    }

    @Synchronized
    fun isCurrent(generation: Long): Boolean = generation == current
}
