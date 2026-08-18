package com.example.monitoring

/**
 * Observable UI state machine representing the lifecycle of the monitoring loop.
 */
sealed interface MonitoringState {
    data object Idle : MonitoringState
    data object RequestingPermission : MonitoringState
    data object Starting : MonitoringState
    data object Capturing : MonitoringState
    data class Analyzing(val startTimeMs: Long = System.currentTimeMillis()) : MonitoringState
    data class Waiting(val remainingSeconds: Int, val totalSeconds: Int) : MonitoringState
    data object Stopping : MonitoringState
    data class Error(val message: String, val canRetry: Boolean = true) : MonitoringState
}
