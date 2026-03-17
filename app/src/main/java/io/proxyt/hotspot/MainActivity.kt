package io.proxyt.hotspot

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var statusView: TextView
    private lateinit var detectedAddressView: TextView
    private lateinit var logView: TextView
    private lateinit var portEdit: TextInputEditText
    private lateinit var baseUrlEdit: TextInputEditText
    private lateinit var debugSwitch: MaterialCheckBox
    private lateinit var startButton: MaterialButton
    private lateinit var stopButton: MaterialButton

    private var pendingStartConfig: ProxyConfig? = null
    private lateinit var statusPreferenceListener: SharedPreferences.OnSharedPreferenceChangeListener

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val configToStart = pendingStartConfig
        if (granted && configToStart != null) {
            startProxyService(configToStart)
            pendingStartConfig = null
            return@registerForActivityResult
        }

        if (configToStart != null) {
            persistNotificationPermissionFailure(configToStart)
            showNotificationPermissionDeniedDialog()
        }
    }

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            renderLogs()
            refreshHandler.postDelayed(this, 2_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.statusText)
        detectedAddressView = findViewById(R.id.detectedAddressText)
        logView = findViewById(R.id.logText)
        portEdit = findViewById(R.id.portEdit)
        baseUrlEdit = findViewById(R.id.baseUrlEdit)
        debugSwitch = findViewById(R.id.debugSwitch)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)

        ProxyPreferences.reconcileStatus(this)
        statusPreferenceListener = ProxyPreferences.registerStatusListener(this) {
            runOnUiThread {
                renderStatus()
            }
        }

        val config = ProxyPreferences.loadConfig(this)
        portEdit.setText(config.port.toString())
        baseUrlEdit.setText(config.advertisedBaseUrl)
        debugSwitch.isChecked = config.debug

        findViewById<MaterialButton>(R.id.detectButton).setOnClickListener {
            val port = parsePort() ?: return@setOnClickListener
            val detected = HotspotAddressDetector.detectPrivateIpv4()
            if (detected == null) {
                Toast.makeText(this, "No private hotspot-style IPv4 address detected", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            baseUrlEdit.setText("http://$detected:$port")
            renderStatus()
        }

        findViewById<MaterialButton>(R.id.refreshLogsButton).setOnClickListener {
            renderLogs()
        }

        findViewById<MaterialButton>(R.id.clearLogsButton).setOnClickListener {
            ProxyPreferences.clearLogs(this)
            renderLogs()
            Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
        }

        startButton.setOnClickListener {
            val configToStart = readConfigFromUi() ?: return@setOnClickListener
            ProxyPreferences.saveConfig(this, configToStart)
            requestNotificationPermissionThenStart(configToStart)
        }

        stopButton.setOnClickListener {
            startService(
                Intent(this, ProxyService::class.java).apply {
                    action = ProxyService.ACTION_STOP
                },
            )
        }

        renderStatus()
        renderLogs()
    }

    override fun onStart() {
        super.onStart()
        renderStatus()
        refreshHandler.post(refreshRunnable)
    }

    override fun onStop() {
        refreshHandler.removeCallbacks(refreshRunnable)
        super.onStop()
    }

    override fun onDestroy() {
        ProxyPreferences.unregisterStatusListener(this, statusPreferenceListener)
        super.onDestroy()
    }

    private fun requestNotificationPermissionThenStart(config: ProxyConfig) {
        if (hasNotificationPermission()) {
            startProxyService(config)
            return
        }

        pendingStartConfig = config
        val explanation = getString(R.string.notification_permission_required_message)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.notification_permission_required_title)
            .setMessage(explanation)
            .setCancelable(false)
            .setPositiveButton(R.string.notification_permission_continue) { _, _ ->
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                persistNotificationPermissionFailure(config)
            }
            .show()
    }

    private fun startProxyService(config: ProxyConfig) {
        ContextCompat.startForegroundService(
            this,
            Intent(this, ProxyService::class.java).apply {
                action = ProxyService.ACTION_START
                putExtra(ProxyService.EXTRA_PORT, config.port)
                putExtra(ProxyService.EXTRA_ADVERTISED_BASE_URL, config.advertisedBaseUrl)
                putExtra(ProxyService.EXTRA_DEBUG, config.debug)
            },
        )
    }

    private fun persistNotificationPermissionFailure(config: ProxyConfig) {
        val resolvedBaseUrl = resolveAdvertisedBaseUrl(config)
        ProxyPreferences.setStatus(
            this,
            ProxyStatus(
                desiredRunning = true,
                state = ProxyRuntimeState.Failed,
                activeUrl = resolvedBaseUrl,
                lastExitCode = null,
                message = getString(R.string.notification_permission_required_short),
                lastFailureReason = getString(R.string.notification_permission_required_message),
            ),
        )
        renderStatus()
    }

    private fun showNotificationPermissionDeniedDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.notification_permission_required_title)
            .setMessage(R.string.notification_permission_settings_message)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null),
                    ),
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun readConfigFromUi(): ProxyConfig? {
        val port = parsePort() ?: return null
        return ProxyConfig(
            port = port,
            advertisedBaseUrl = baseUrlEdit.text?.toString()?.trim()?.removeSuffix("/").orEmpty(),
            debug = debugSwitch.isChecked,
        )
    }

    private fun parsePort(): Int? {
        val raw = portEdit.text?.toString()?.trim().orEmpty()
        val port = raw.toIntOrNull()
        if (port == null || port !in 1..65535) {
            Toast.makeText(this, "Enter a valid port between 1 and 65535", Toast.LENGTH_SHORT).show()
            return null
        }
        return port
    }

    private fun resolveAdvertisedBaseUrl(config: ProxyConfig): String {
        if (config.advertisedBaseUrl.isNotBlank()) {
            return config.advertisedBaseUrl.trim().removeSuffix("/")
        }
        val address = HotspotAddressDetector.detectPrivateIpv4() ?: ProxyService.DEFAULT_HOTSPOT_IP
        return "http://$address:${config.port}"
    }

    private fun renderStatus() {
        val port = portEdit.text?.toString()?.trim().takeUnless { it.isNullOrBlank() } ?: "8080"
        val detected = HotspotAddressDetector.detectPrivateIpv4()
        detectedAddressView.text = if (detected == null) {
            getString(R.string.detected_address_missing)
        } else {
            getString(R.string.detected_address_value, detected, port)
        }

        val status = ProxyPreferences.reconcileStatus(this)
        statusView.text = buildString {
            append("State: ")
            append(status.state.name.lowercase().replaceFirstChar { it.titlecase(Locale.US) })
            append("\nDesired: ")
            append(if (status.desiredRunning) "Running" else "Stopped")

            if (status.activeUrl.isNotBlank()) {
                append("\nActive URL: ")
                append(status.activeUrl)
            }
            if (status.message.isNotBlank()) {
                append("\nStatus: ")
                append(status.message)
            }
            if (status.lastFailureReason.isNotBlank()) {
                append("\nLast failure: ")
                append(status.lastFailureReason)
            }
            if (status.lastExitCode != null) {
                append("\nLast exit code: ")
                append(status.lastExitCode)
            }
            if (status.startTimestampMs != null) {
                append("\nStarted: ")
                append(formatTimestamp(status.startTimestampMs))
            }
            if (status.lastSuccessfulStartTimestampMs != null) {
                append("\nLast successful start: ")
                append(formatTimestamp(status.lastSuccessfulStartTimestampMs))
            }
            if (!hasNotificationPermission()) {
                append("\nNotifications required before the proxy can start on Android 13+.")
            }
        }

        startButton.isEnabled = status.state != ProxyRuntimeState.Starting && status.state != ProxyRuntimeState.Running
        stopButton.isEnabled = status.desiredRunning || status.isActive
    }

    private fun renderLogs() {
        logView.text = ProxyPreferences.readLogTail(this)
    }

    private fun formatTimestamp(timestampMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestampMs))
}
