package com.example.monitoring

sealed interface MonitoringEvent {
    data class ShowToast(val message: String) : MonitoringEvent
    data class PermissionDenied(val message: String) : MonitoringEvent
}
