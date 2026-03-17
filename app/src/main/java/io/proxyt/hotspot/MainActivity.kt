package io.proxyt.hotspot

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {
    private lateinit var stateView: TextView
    private lateinit var statusMessageView: TextView
    private lateinit var statusUrlView: TextView
    private lateinit var errorCategoryView: TextView
    private lateinit var lastFailureView: TextView
    private lateinit var lastExitCodeView: TextView
    private lateinit var nextActionView: TextView
    private lateinit var detectedAddressView: TextView
    private lateinit var logView: TextView
    private lateinit var portEdit: TextInputEditText
    private lateinit var baseUrlEdit: TextInputEditText
    private lateinit var debugSwitch: MaterialCheckBox
    private lateinit var startButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    private lateinit var copyLastErrorButton: MaterialButton

    private val refreshHandler = Handler(Looper.getMainLooper())
    private var latestStatus: ProxyStatus? = null
    private val refreshRunnable = object : Runnable {
        override fun run() {
            renderStatus()
            renderLogs()
            refreshHandler.postDelayed(this, 2_000)
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            renderStatus()
            renderLogs()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        stateView = findViewById(R.id.stateValueText)
        statusMessageView = findViewById(R.id.statusMessageText)
        statusUrlView = findViewById(R.id.statusUrlText)
        errorCategoryView = findViewById(R.id.errorCategoryText)
        lastFailureView = findViewById(R.id.lastFailureText)
        lastExitCodeView = findViewById(R.id.lastExitCodeText)
        nextActionView = findViewById(R.id.nextActionText)
        detectedAddressView = findViewById(R.id.detectedAddressText)
        logView = findViewById(R.id.logText)
        portEdit = findViewById(R.id.portEdit)
        baseUrlEdit = findViewById(R.id.baseUrlEdit)
        debugSwitch = findViewById(R.id.debugSwitch)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        copyLastErrorButton = findViewById(R.id.copyLastErrorButton)

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

        copyLastErrorButton.setOnClickListener {
            val status = latestStatus
            val error = status?.error ?: return@setOnClickListener
            val clipboard = getSystemService(ClipboardManager::class.java)
            val content = buildString {
                append(error.title)
                append('\n')
                append(error.detail)
                if (status.lastExitCode != null) {
                    append("\nExit code: ")
                    append(status.lastExitCode)
                }
                if (error.recommendedAction.isNotBlank()) {
                    append("\nRecommended action: ")
                    append(error.recommendedAction)
                }
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("Proxy last error", content))
            Toast.makeText(this, R.string.last_error_copied, Toast.LENGTH_SHORT).show()
        }

        startButton.setOnClickListener {
            val configToStart = readConfigFromUi() ?: return@setOnClickListener
            ProxyPreferences.saveConfig(this, configToStart)
            ContextCompat.startForegroundService(
                this,
                Intent(this, ProxyService::class.java).apply {
                    action = ProxyService.ACTION_START
                    putExtra(ProxyService.EXTRA_PORT, configToStart.port)
                    putExtra(ProxyService.EXTRA_ADVERTISED_BASE_URL, configToStart.advertisedBaseUrl)
                    putExtra(ProxyService.EXTRA_DEBUG, configToStart.debug)
                },
            )
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
        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            IntentFilter(ProxyService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        refreshHandler.post(refreshRunnable)
    }

    override fun onStop() {
        unregisterReceiver(statusReceiver)
        refreshHandler.removeCallbacks(refreshRunnable)
        super.onStop()
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

    private fun renderStatus() {
        val port = portEdit.text?.toString()?.trim().takeUnless { it.isNullOrBlank() } ?: "8080"
        val detected = HotspotAddressDetector.detectPrivateIpv4()
        detectedAddressView.text = if (detected == null) {
            getString(R.string.detected_address_missing)
        } else {
            getString(R.string.detected_address_value, detected, port)
        }

        val status = ProxyPreferences.readStatus(this)
        latestStatus = status

        stateView.text = getString(if (status.running) R.string.state_running else R.string.state_stopped)
        statusMessageView.text = status.message
        statusUrlView.text = status.activeUrl.ifBlank { getString(R.string.no_active_url) }
        errorCategoryView.text = status.error?.let(::errorLabel) ?: getString(R.string.error_state_healthy)
        errorCategoryView.setTextColor(ContextCompat.getColor(this, errorColor(status.error?.category)))
        lastFailureView.text = status.error?.detail ?: getString(R.string.no_failure_recorded)
        lastExitCodeView.text = status.lastExitCode?.toString() ?: getString(R.string.no_exit_code)
        nextActionView.text = status.error?.recommendedAction ?: defaultRecommendedAction(status)
        copyLastErrorButton.visibility = if (status.error == null) View.GONE else View.VISIBLE

        startButton.isEnabled = !status.running
        stopButton.isEnabled = status.running
    }

    private fun renderLogs() {
        logView.text = ProxyPreferences.readLogTail(this)
    }

    private fun errorLabel(error: ProxyErrorInfo): String =
        when (error.category) {
            ProxyErrorCategory.MISSING_BINARY -> getString(R.string.error_state_missing_binary)
            ProxyErrorCategory.INVALID_CONFIG -> getString(R.string.error_state_invalid_config)
            ProxyErrorCategory.PORT_IN_USE -> getString(R.string.error_state_port_in_use)
            ProxyErrorCategory.PROXY_EXIT -> getString(R.string.error_state_proxy_exit)
            ProxyErrorCategory.PERMISSION_REQUIRED -> getString(R.string.error_state_permission)
            ProxyErrorCategory.STARTUP_FAILURE -> getString(R.string.error_state_startup_failure)
            ProxyErrorCategory.NONE -> getString(R.string.error_state_healthy)
        }

    private fun errorColor(category: ProxyErrorCategory?): Int =
        when (category) {
            null, ProxyErrorCategory.NONE -> R.color.proxy_success
            ProxyErrorCategory.INVALID_CONFIG -> R.color.proxy_warning
            ProxyErrorCategory.PERMISSION_REQUIRED -> R.color.proxy_warning
            ProxyErrorCategory.PORT_IN_USE -> R.color.proxy_error
            ProxyErrorCategory.MISSING_BINARY -> R.color.proxy_error
            ProxyErrorCategory.PROXY_EXIT -> R.color.proxy_error
            ProxyErrorCategory.STARTUP_FAILURE -> R.color.proxy_error
        }

    private fun defaultRecommendedAction(status: ProxyStatus): String =
        if (status.running) {
            getString(R.string.next_action_running)
        } else {
            getString(R.string.next_action_idle)
        }
}
