package io.proxyt.hotspot

object MainScreenFormatter {
    fun stateLabel(state: ProxyRuntimeState): String =
        when (state) {
            ProxyRuntimeState.Idle -> "Stopped"
            ProxyRuntimeState.Starting -> "Starting"
            ProxyRuntimeState.Running -> "Running"
            ProxyRuntimeState.Stopping -> "Stopping"
            ProxyRuntimeState.Failed -> "Failed"
        }

    fun errorLabel(category: ProxyErrorCategory?): String =
        when (category) {
            null, ProxyErrorCategory.NONE -> "Healthy"
            ProxyErrorCategory.MISSING_BINARY -> "Missing binary"
            ProxyErrorCategory.INVALID_CONFIG -> "Invalid config"
            ProxyErrorCategory.PORT_IN_USE -> "Port in use"
            ProxyErrorCategory.PROXY_EXIT -> "Proxy exited"
            ProxyErrorCategory.PERMISSION_REQUIRED -> "Permission issue"
            ProxyErrorCategory.STARTUP_FAILURE -> "Startup failure"
        }

    fun defaultRecommendedAction(
        status: ProxyStatus,
        hasNotificationPermission: Boolean,
        ignoringBatteryOptimizations: Boolean,
    ): String =
        when {
            status.error != null -> status.error.recommendedAction
            !hasNotificationPermission ->
                "This app depends on foreground-service notifications. Grant notification permission on Android 13 or newer before starting the proxy."

            !ignoringBatteryOptimizations ->
                "Battery optimization is still enabled for this app. Some devices will throttle or stop hotspot proxy traffic unless you exempt the app."

            status.state == ProxyRuntimeState.Running ->
                "Leave the app open and point your client at the active URL shown above."

            else ->
                "Start the proxy when you are ready to serve requests."
        }
}
