package io.proxyt.hotspot

object DiagnosticsFormatter {
    fun buildClipboardError(status: ProxyStatus?): String? {
        if (status == null) {
            return null
        }

        val error = status.error
        val detail = error?.detail ?: status.lastFailureReason
        if (detail.isBlank()) {
            return null
        }

        return buildString {
            append(error?.title ?: status.message)
            append('\n')
            append(detail)
            if (status.lastExitCode != null) {
                append("\nExit code: ")
                append(status.lastExitCode)
            }
            val action = error?.recommendedAction
            if (!action.isNullOrBlank()) {
                append("\nRecommended action: ")
                append(action)
            }
        }
    }

    fun buildShareDiagnostics(
        status: ProxyStatus,
        startupEvents: String,
        logTail: String,
    ): String = buildString {
        append("State: ")
        append(MainScreenFormatter.stateLabel(status.state))
        append("\nMessage: ")
        append(status.message)
        append("\nActive URL: ")
        append(status.activeUrl.ifBlank { "Not available" })
        append("\nError: ")
        append(status.error?.title ?: status.lastFailureReason.ifBlank { "None" })
        if (status.lastExitCode != null) {
            append("\nExit code: ")
            append(status.lastExitCode)
        }
        append("\n\nStartup events:\n")
        append(startupEvents.ifBlank { "No recent startup events." })
        append("\n\nRecent logs:\n")
        append(logTail.ifBlank { "No logs yet." })
    }
}
