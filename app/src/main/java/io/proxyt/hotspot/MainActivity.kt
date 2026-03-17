package io.proxyt.hotspot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {
    private lateinit var statusView: TextView
    private lateinit var detectedAddressView: TextView
    private lateinit var logView: TextView
    private lateinit var portEdit: TextInputEditText
    private lateinit var baseUrlEdit: TextInputEditText
    private lateinit var debugSwitch: MaterialCheckBox
    private lateinit var startButton: MaterialButton
    private lateinit var stopButton: MaterialButton

    private val refreshHandler = Handler(Looper.getMainLooper())
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

        statusView = findViewById(R.id.statusText)
        detectedAddressView = findViewById(R.id.detectedAddressText)
        logView = findViewById(R.id.logText)
        portEdit = findViewById(R.id.portEdit)
        baseUrlEdit = findViewById(R.id.baseUrlEdit)
        debugSwitch = findViewById(R.id.debugSwitch)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)

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
        statusView.text = buildString {
            append(if (status.running) "Running" else "Stopped")
            if (status.activeUrl.isNotBlank()) {
                append("\n")
                append(status.activeUrl)
            }
            if (status.message.isNotBlank()) {
                append("\n")
                append(status.message)
            }
            if (!status.running && status.lastExitCode != null) {
                append("\nExit code: ")
                append(status.lastExitCode)
            }
        }

        startButton.isEnabled = !status.running
        stopButton.isEnabled = status.running
    }

    private fun renderLogs() {
        logView.text = ProxyPreferences.readLogTail(this)
    }
}
