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
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {
    private lateinit var statusView: TextView
    private lateinit var detectedAddressView: TextView
    private lateinit var logView: TextView
    private lateinit var portLayout: TextInputLayout
    private lateinit var portEdit: TextInputEditText
    private lateinit var baseUrlLayout: TextInputLayout
    private lateinit var baseUrlEdit: TextInputEditText
    private lateinit var localAddressLayout: TextInputLayout
    private lateinit var localAddressPicker: MaterialAutoCompleteTextView
    private lateinit var debugSwitch: MaterialCheckBox
    private lateinit var startButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    private lateinit var copyUrlButton: MaterialButton

    private var localCandidates: List<HotspotAddressCandidate> = emptyList()
    private var suppressValidationCallbacks = false

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
        portLayout = findViewById(R.id.portLayout)
        portEdit = findViewById(R.id.portEdit)
        baseUrlLayout = findViewById(R.id.baseUrlLayout)
        baseUrlEdit = findViewById(R.id.baseUrlEdit)
        localAddressLayout = findViewById(R.id.localAddressLayout)
        localAddressPicker = findViewById(R.id.localAddressPicker)
        debugSwitch = findViewById(R.id.debugSwitch)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        copyUrlButton = findViewById(R.id.copyUrlButton)

        val config = ProxyPreferences.loadConfig(this)
        portEdit.setText(getString(R.string.port_value, config.port))
        baseUrlEdit.setText(config.advertisedBaseUrl)
        localAddressPicker.setText(config.selectedLocalAddress, false)
        debugSwitch.isChecked = config.debug

        refreshLocalAddressOptions()
        renderValidation()

        findViewById<MaterialButton>(R.id.detectButton).setOnClickListener {
            refreshLocalAddressOptions()
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

        copyUrlButton.setOnClickListener {
            val effectiveUrl = validateUi(showErrors = false).effectiveUrl
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("Proxy control URL", effectiveUrl))
            Toast.makeText(this, "Copied control URL", Toast.LENGTH_SHORT).show()
        }

        portEdit.doAfterTextChanged {
            if (!suppressValidationCallbacks) {
                renderValidation()
                renderStatus()
            }
        }
        baseUrlEdit.doAfterTextChanged {
            if (!suppressValidationCallbacks) {
                renderValidation()
                renderStatus()
            }
        }
        localAddressPicker.doAfterTextChanged {
            if (!suppressValidationCallbacks) {
                renderValidation()
                renderStatus()
            }
        }

        startButton.setOnClickListener {
            val validation = validateUi(showErrors = true)
            val configToStart = validation.config ?: return@setOnClickListener
            ProxyPreferences.saveConfig(this, configToStart)
            ContextCompat.startForegroundService(
                this,
                Intent(this, ProxyService::class.java).apply {
                    action = ProxyService.ACTION_START
                    putExtra(ProxyService.EXTRA_PORT, configToStart.port)
                    putExtra(ProxyService.EXTRA_ADVERTISED_BASE_URL, configToStart.advertisedBaseUrl)
                    putExtra(ProxyService.EXTRA_SELECTED_LOCAL_ADDRESS, configToStart.selectedLocalAddress)
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

    private fun refreshLocalAddressOptions() {
        localCandidates = HotspotAddressDetector.detectCandidates()
        val savedSelection = localAddressPicker.text?.toString()?.trim().orEmpty()
        val options = buildList {
            localCandidates.forEach { candidate ->
                add(candidate.address)
            }
            if (savedSelection.isNotBlank() && localCandidates.none { it.address == savedSelection }) {
                add(savedSelection)
            }
        }
        localAddressPicker.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_dropdown_item_1line,
                options,
            ),
        )
        if (savedSelection.isNotBlank()) {
            suppressValidationCallbacks = true
            localAddressPicker.setText(savedSelection, false)
            suppressValidationCallbacks = false
        } else if (localCandidates.isNotEmpty()) {
            suppressValidationCallbacks = true
            localAddressPicker.setText(localCandidates.first().address, false)
            suppressValidationCallbacks = false
        }
        renderValidation()
    }

    private fun validateUi(showErrors: Boolean): ProxyConfigValidationResult {
        val validation = ProxyConfigValidator.validate(
            portInput = portEdit.text?.toString().orEmpty(),
            advertisedBaseUrlInput = baseUrlEdit.text?.toString().orEmpty(),
            selectedLocalAddressInput = localAddressPicker.text?.toString().orEmpty(),
            debug = debugSwitch.isChecked,
            localCandidates = localCandidates,
        )
        if (showErrors) {
            portLayout.error = validation.portError
            baseUrlLayout.error = validation.baseUrlError
            localAddressLayout.error = validation.localAddressError
        } else {
            portLayout.error = null
            baseUrlLayout.error = null
            localAddressLayout.error = null
        }
        baseUrlLayout.helperText = validation.baseUrlWarning
            ?: getString(R.string.base_url_helper_text)
        localAddressLayout.helperText = validation.localAddressWarning
            ?: getString(R.string.local_address_helper_text)
        copyUrlButton.isEnabled = validation.effectiveUrl.isNotBlank()
        return validation
    }

    private fun renderValidation() {
        validateUi(showErrors = false)
    }

    private fun renderStatus() {
        val validation = validateUi(showErrors = false)
        val candidateSummary = if (localCandidates.isEmpty()) {
            getString(R.string.detected_address_missing)
        } else {
            localCandidates.joinToString(separator = "\n") { candidate ->
                "- ${candidate.address} (${candidate.interfaceName}, ${candidate.kind.lowercase()})"
            }
        }
        detectedAddressView.text = buildString {
            append(getString(R.string.effective_url_value, validation.effectiveUrl))
            append("\n\n")
            append(getString(R.string.detected_address_candidates_label))
            append("\n")
            append(candidateSummary)
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
