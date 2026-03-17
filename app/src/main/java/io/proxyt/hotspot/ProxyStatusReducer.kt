package io.proxyt.hotspot

object ProxyStatusReducer {
    fun reconcilePersistedStatus(
        status: ProxyStatus,
        isServiceRunning: Boolean,
    ): ProxyStatus {
        if (isServiceRunning || !status.isActive) {
            return status
        }

        return if (status.desiredRunning) {
            val detail = status.lastFailureReason.ifBlank { "Android is no longer running the proxy service." }
            status.copy(
                state = ProxyRuntimeState.Failed,
                message = "Proxy stopped unexpectedly",
                lastFailureReason = detail,
                error = status.error ?: ProxyErrorInfo(
                    category = ProxyErrorCategory.STARTUP_FAILURE,
                    title = "Proxy stopped unexpectedly",
                    detail = detail,
                    recommendedAction = "Try starting the proxy again. If it keeps stopping, copy the last error and inspect the logs.",
                ),
            )
        } else {
            status.copy(
                state = ProxyRuntimeState.Idle,
                message = "Proxy idle",
                error = null,
            )
        }
    }
}
