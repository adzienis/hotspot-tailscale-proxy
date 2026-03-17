package io.proxyt.hotspot

enum class StartRequestDecision {
    StartImmediately,
    RequestNotificationPermission,
}

object StartRequestPolicy {
    fun decide(hasNotificationPermission: Boolean): StartRequestDecision =
        if (hasNotificationPermission) {
            StartRequestDecision.StartImmediately
        } else {
            StartRequestDecision.RequestNotificationPermission
        }
}
