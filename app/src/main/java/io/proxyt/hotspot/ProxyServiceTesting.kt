package io.proxyt.hotspot

object ProxyServiceFailureClassifier {
    fun classify(
        detail: String,
        exitCode: Int? = null,
        logTail: String = "",
    ): ProxyErrorInfo {
        val combined = buildString {
            append(detail)
            if (logTail.isNotBlank()) {
                append('\n')
                append(logTail)
            }
        }.lowercase()

        return when {
            "address already in use" in combined || "bind" in combined || "port already in use" in combined -> {
                ProxyErrorInfo(
                    category = ProxyErrorCategory.PORT_IN_USE,
                    title = "Port is already in use",
                    detail = "Another process is already bound to the selected port.",
                    recommendedAction = "Choose a different listen port or stop the app that is already using this port.",
                )
            }

            "permission denied" in combined || "operation not permitted" in combined || "not allowed" in combined -> {
                ProxyErrorInfo(
                    category = ProxyErrorCategory.PERMISSION_REQUIRED,
                    title = "Permission issue",
                    detail = "Android or the proxy process denied access needed to start cleanly.",
                    recommendedAction = "Grant the required permission or choose a different port, then try starting the proxy again.",
                )
            }

            exitCode != null -> {
                ProxyErrorInfo(
                    category = ProxyErrorCategory.PROXY_EXIT,
                    title = "Proxy process exited",
                    detail = "The bundled proxy exited with code $exitCode.",
                    recommendedAction = "Retry once. If it exits again, copy the last error and inspect the logs for more detail.",
                )
            }

            else -> {
                ProxyErrorInfo(
                    category = ProxyErrorCategory.STARTUP_FAILURE,
                    title = "Proxy failed to start",
                    detail = detail,
                    recommendedAction = "Try again. If the problem repeats, copy the last error and inspect the logs.",
                )
            }
        }
    }
}

object ProxyServiceStatusFactory {
    fun alreadyRunning(currentStatus: ProxyStatus): ProxyStatus =
        currentStatus.copy(
            desiredRunning = true,
            state = ProxyRuntimeState.Running,
            message = "Proxy already running",
            lastFailureReason = "",
            error = null,
        )

    fun starting(
        previousStatus: ProxyStatus,
        activeUrl: String,
        diagnostics: ProxyDiagnostics,
        startTimestampMs: Long,
        probeStatus: String,
        probeTarget: String,
        probeDetail: String,
    ): ProxyStatus =
        ProxyStatus(
            desiredRunning = true,
            state = ProxyRuntimeState.Starting,
            activeUrl = activeUrl,
            lastExitCode = null,
            message = "Starting proxy",
            startTimestampMs = startTimestampMs,
            lastSuccessfulStartTimestampMs = previousStatus.lastSuccessfulStartTimestampMs,
            error = null,
            diagnostics = diagnostics.copy(
                lastProbeStatus = probeStatus,
                lastProbeTarget = probeTarget,
                lastProbeDetail = probeDetail,
            ),
        )

    fun running(
        activeUrl: String,
        startTimestampMs: Long,
        processPid: Long?,
        diagnostics: ProxyDiagnostics,
        probeStatus: String,
        probeTarget: String,
        probeDetail: String,
        port: Int,
    ): ProxyStatus =
        ProxyStatus(
            desiredRunning = true,
            state = ProxyRuntimeState.Running,
            activeUrl = activeUrl,
            lastExitCode = null,
            message = "Serving on $activeUrl",
            startTimestampMs = startTimestampMs,
            lastSuccessfulStartTimestampMs = startTimestampMs,
            error = null,
            diagnostics = diagnostics.copy(
                currentPid = processPid,
                portBindResult = "Bind confirmed on port $port",
                lastProbeStatus = probeStatus,
                lastProbeTarget = probeTarget,
                lastProbeDetail = probeDetail,
            ),
        )

    fun healthCheckFailed(
        previousStatus: ProxyStatus,
        activeUrl: String,
        processPid: Long?,
        diagnostics: ProxyDiagnostics,
        error: ProxyErrorInfo,
        startTimestampMs: Long,
        lastExitCode: Int?,
        probeStatus: String,
        probeTarget: String,
        probeDetail: String,
    ): ProxyStatus =
        ProxyStatus(
            desiredRunning = true,
            state = ProxyRuntimeState.Failed,
            activeUrl = activeUrl,
            lastExitCode = lastExitCode,
            message = error.title,
            lastFailureReason = error.detail,
            startTimestampMs = startTimestampMs,
            lastSuccessfulStartTimestampMs = previousStatus.lastSuccessfulStartTimestampMs,
            error = error,
            diagnostics = diagnostics.copy(
                currentPid = processPid,
                portBindResult = "Process started but health check did not complete",
                lastProbeStatus = probeStatus,
                lastProbeTarget = probeTarget,
                lastProbeDetail = probeDetail,
            ),
        )

    fun idleAfterStop(
        currentStatus: ProxyStatus,
        activeUrl: String,
        exitCode: Int,
    ): ProxyStatus =
        ProxyStatus(
            desiredRunning = false,
            state = ProxyRuntimeState.Idle,
            activeUrl = currentStatus.activeUrl.ifBlank { activeUrl },
            lastExitCode = exitCode,
            message = "Proxy stopped",
            startTimestampMs = currentStatus.startTimestampMs,
            lastSuccessfulStartTimestampMs = currentStatus.lastSuccessfulStartTimestampMs,
            error = null,
            diagnostics = currentStatus.diagnostics.copy(currentPid = null),
        )

    fun exitedUnexpectedly(
        currentStatus: ProxyStatus,
        activeUrl: String,
        exitCode: Int,
        error: ProxyErrorInfo,
    ): ProxyStatus =
        ProxyStatus(
            desiredRunning = true,
            state = ProxyRuntimeState.Failed,
            activeUrl = currentStatus.activeUrl.ifBlank { activeUrl },
            lastExitCode = exitCode,
            message = error.title,
            lastFailureReason = error.detail,
            startTimestampMs = currentStatus.startTimestampMs,
            lastSuccessfulStartTimestampMs = currentStatus.lastSuccessfulStartTimestampMs,
            error = error,
            diagnostics = currentStatus.diagnostics.copy(
                currentPid = null,
                portBindResult = "Proxy process exited before shutdown",
            ),
        )
}
