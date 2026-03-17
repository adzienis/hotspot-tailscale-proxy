package io.proxyt.hotspot

import android.content.Context
import java.io.File

data class ProxyConfig(
    val port: Int = 8080,
    val advertisedBaseUrl: String = "",
    val selectedLocalAddress: String = "",
    val debug: Boolean = false,
)

data class ProxyStatus(
    val running: Boolean,
    val activeUrl: String,
    val lastExitCode: Int?,
    val message: String,
)

object ProxyPreferences {
    private const val NAME = "proxy_preferences"
    private const val KEY_PORT = "port"
    private const val KEY_ADVERTISED_BASE_URL = "advertised_base_url"
    private const val KEY_SELECTED_LOCAL_ADDRESS = "selected_local_address"
    private const val KEY_DEBUG = "debug"
    private const val KEY_RUNNING = "running"
    private const val KEY_ACTIVE_URL = "active_url"
    private const val KEY_LAST_EXIT_CODE = "last_exit_code"
    private const val KEY_MESSAGE = "message"

    fun loadConfig(context: Context): ProxyConfig {
        val preferences = preferences(context)
        return ProxyConfig(
            port = preferences.getInt(KEY_PORT, 8080),
            advertisedBaseUrl = preferences.getString(KEY_ADVERTISED_BASE_URL, "").orEmpty(),
            selectedLocalAddress = preferences.getString(KEY_SELECTED_LOCAL_ADDRESS, "").orEmpty(),
            debug = preferences.getBoolean(KEY_DEBUG, false),
        )
    }

    fun saveConfig(context: Context, config: ProxyConfig) {
        preferences(context).edit()
            .putInt(KEY_PORT, config.port)
            .putString(KEY_ADVERTISED_BASE_URL, config.advertisedBaseUrl)
            .putString(KEY_SELECTED_LOCAL_ADDRESS, config.selectedLocalAddress)
            .putBoolean(KEY_DEBUG, config.debug)
            .apply()
    }

    fun readStatus(context: Context): ProxyStatus {
        val preferences = preferences(context)
        return ProxyStatus(
            running = preferences.getBoolean(KEY_RUNNING, false),
            activeUrl = preferences.getString(KEY_ACTIVE_URL, "").orEmpty(),
            lastExitCode = if (preferences.contains(KEY_LAST_EXIT_CODE)) {
                preferences.getInt(KEY_LAST_EXIT_CODE, 0)
            } else {
                null
            },
            message = preferences.getString(KEY_MESSAGE, "Proxy idle").orEmpty(),
        )
    }

    fun setStatus(
        context: Context,
        running: Boolean,
        activeUrl: String,
        lastExitCode: Int?,
        message: String,
    ) {
        val editor = preferences(context).edit()
            .putBoolean(KEY_RUNNING, running)
            .putString(KEY_ACTIVE_URL, activeUrl)
            .putString(KEY_MESSAGE, message)

        if (lastExitCode == null) {
            editor.remove(KEY_LAST_EXIT_CODE)
        } else {
            editor.putInt(KEY_LAST_EXIT_CODE, lastExitCode)
        }

        editor.apply()
    }

    fun logFile(context: Context): File = File(context.filesDir, "proxyt.log")

    fun readLogTail(context: Context, maxChars: Int = 12_000): String {
        val logFile = logFile(context)
        if (!logFile.exists()) {
            return "No logs yet."
        }

        val contents = logFile.readText()
        return if (contents.length <= maxChars) contents else contents.takeLast(maxChars)
    }

    fun clearLogs(context: Context) {
        logFile(context).writeText("")
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
