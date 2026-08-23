package com.example.roadlog

/** States that are externally meaningful for a recording service. */
enum class RecordingState {
    IDLE,
    PREPARING,
    RECORDING,
    FINALIZING,
    FAILED,
    ABORTED
}

object RecordingStateMachine {
    fun canStart(state: RecordingState): Boolean = state == RecordingState.IDLE

    fun canStop(state: RecordingState): Boolean = state == RecordingState.PREPARING ||
        state == RecordingState.RECORDING

    fun canComplete(state: RecordingState): Boolean = state == RecordingState.FINALIZING
}
