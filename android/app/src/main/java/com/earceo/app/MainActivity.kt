package com.earceo.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.vision.headset.open.VHOError
import com.vision.headset.open.VHOManager
import com.vision.headset.open.VHOManagerConfig
import com.vision.headset.open.VHORecordListener
import com.vision.headset.open.VHORecordType
import com.vision.headset.open.VHOTextStreamResult
import com.vision.headset.open.VHOTextStreamResultType
import com.vision.headset.open.auth.VHOCredentials
import com.vision.headset.open.auth.VHODeviceVerifyPolicy
import com.vision.headset.sdk.OnConnectChangeListener
import com.vision.headset.sdk.OnDialogListener
import com.vision.headset.sdk.OnResultListener
import com.vision.headset.sdk.entity.PowerEntity
import com.vision.headset.sdk.entity.RecordDataEntity
import com.vision.headset.sdk.enums.GaiaConnectStatus
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * EarCEO Android hardware probe.
 *
 * This activity deliberately keeps the vendor integration separate from the
 * backend agent system. It verifies, in order:
 * 1. local SDK initialization and optional text-stream authorization;
 * 2. iFLYBUDS SPP connection;
 * 3. raw PCM delivery;
 * 4. optional partial/final ASR callbacks.
 *
 * Empty credentials are a supported development mode: connection and PCM can
 * still be tested while text-stream remains disabled.
 */
class MainActivity : Activity() {

    private lateinit var sdkStatus: TextView
    private lateinit var capabilityStatus: TextView
    private lateinit var headsetStatus: TextView
    private lateinit var pcmStatus: TextView
    private lateinit var recordingFileStatus: TextView
    private lateinit var partialText: TextView
    private lateinit var transcriptText: TextView
    private lateinit var initializeButton: Button
    private lateinit var connectButton: Button
    private lateinit var recordButton: Button
    private lateinit var exportRecordingButton: Button

    private val transcript = StringBuilder()
    private val audioFileExecutor = Executors.newSingleThreadExecutor()
    private var pendingAfterPermission: (() -> Unit)? = null
    private var activeWavRecorder: WavRecorder? = null
    private var latestWavFile: File? = null
    private var pendingExportFile: File? = null

    @Volatile private var sdkReady = false
    @Volatile private var textStreamAvailable = false
    @Volatile private var headsetReady = false
    @Volatile private var recording = false
    @Volatile private var wavFinalizing = false
    @Volatile private var pcmFrames = 0L
    @Volatile private var pcmBytes = 0L

    private val hasCredentials: Boolean
        get() = BuildConfig.VIAIM_APP_KEY.isNotBlank() &&
            BuildConfig.VIAIM_APP_SECRET.isNotBlank()

    private val credentials: VHOCredentials
        get() = VHOCredentials.AppSecret(
            BuildConfig.VIAIM_APP_KEY,
            BuildConfig.VIAIM_APP_SECRET,
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildInterface())
        refreshButtons()
        initializeSdk()
    }

    override fun onDestroy() {
        runCatching {
            if (recording) VHOManager.stopLiveRecord()
            VHOManager.setPcmListener(null)
            VHOManager.setRecordListener(null)
            VHOManager.release()
        }
        finishWavRecording(updateUi = false)
        audioFileExecutor.shutdown()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT_WAV) return

        val source = pendingExportFile
        pendingExportFile = null
        val destination = data?.data
        if (resultCode != RESULT_OK || source == null || destination == null) return

        audioFileExecutor.execute {
            val error = runCatching {
                contentResolver.openOutputStream(destination, "w")?.use { output ->
                    source.inputStream().buffered().use { input -> input.copyTo(output) }
                } ?: error("Unable to open the selected destination")
            }.exceptionOrNull()

            ui {
                if (error == null) {
                    toast(getString(R.string.recording_exported))
                } else {
                    Log.e(TAG, "Unable to export WAV", error)
                    toast(
                        getString(
                            R.string.recording_export_failed,
                            error.message ?: error.javaClass.simpleName,
                        ),
                    )
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        val action = pendingAfterPermission
        pendingAfterPermission = null

        if (granted) {
            action?.invoke()
        } else {
            toast(
                when (requestCode) {
                    REQUEST_BLUETOOTH -> getString(R.string.bluetooth_permission_denied)
                    REQUEST_MICROPHONE -> getString(R.string.microphone_permission_denied)
                    else -> getString(R.string.permission_denied)
                },
            )
        }
    }

    private fun buildInterface(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(245, 247, 252))
            isFillViewport = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(40))
        }
        scroll.addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        content.addView(text(getString(R.string.app_name), 30f, Color.rgb(21, 31, 55), true))
        content.addView(
            text(getString(R.string.tagline), 15f, Color.rgb(85, 98, 126), false).withMargins(
                bottom = 20,
            ),
        )

        sdkStatus = statusCard(getString(R.string.sdk_waiting))
        capabilityStatus = statusCard(getString(R.string.capability_waiting))
        headsetStatus = statusCard(getString(R.string.headset_disconnected))
        pcmStatus = statusCard(getString(R.string.pcm_waiting))
        recordingFileStatus = statusCard(getString(R.string.recording_file_waiting))
        content.addView(sdkStatus)
        content.addView(capabilityStatus)
        content.addView(headsetStatus)
        content.addView(pcmStatus)
        content.addView(recordingFileStatus)

        initializeButton = actionButton(getString(R.string.initialize_sdk)) { initializeSdk() }
        connectButton = actionButton(getString(R.string.connect_headset)) { connectHeadset() }
        recordButton = actionButton(getString(R.string.start_recording)) { toggleRecording() }
        exportRecordingButton = actionButton(getString(R.string.export_recording)) {
            exportLatestRecording()
        }
        content.addView(initializeButton.withMargins(top = 12))
        content.addView(connectButton)
        content.addView(recordButton)
        content.addView(exportRecordingButton)
        content.addView(actionButton(getString(R.string.clear_transcript)) { clearTranscript() })

        content.addView(sectionTitle(getString(R.string.live_partial)))
        partialText = text(getString(R.string.no_partial_result), 18f, Color.rgb(68, 83, 118), false)
            .withPadding(16)
        partialText.setBackgroundColor(Color.WHITE)
        content.addView(
            partialText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(88),
            ).apply { bottomMargin = dp(18) },
        )

        content.addView(sectionTitle(getString(R.string.final_transcript)))
        transcriptText = text(getString(R.string.no_final_result), 18f, Color.rgb(25, 37, 63), false)
            .withPadding(16)
        transcriptText.setBackgroundColor(Color.WHITE)
        transcriptText.gravity = Gravity.TOP
        content.addView(
            transcriptText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(220),
            ),
        )
        return scroll
    }

    private fun initializeSdk() {
        if (recording) return
        sdkReady = false
        textStreamAvailable = false
        sdkStatus.text = getString(R.string.sdk_initializing)
        capabilityStatus.text = getString(R.string.capability_checking)
        refreshButtons()

        if (!hasCredentials) {
            runCatching {
                VHOManager.init(applicationContext) {
                    registerDialogLogging()
                    ui {
                        sdkReady = true
                        sdkStatus.text = getString(R.string.sdk_ready_pcm_only)
                        capabilityStatus.text = getString(R.string.text_stream_not_configured)
                        refreshButtons()
                    }
                }
            }.onFailure { showSdkFailure(it) }
            return
        }

        runCatching {
            VHOManager.initialize(
                applicationContext,
                BuildConfig.VIAIM_APP_KEY,
                BuildConfig.VIAIM_APP_SECRET,
                VHODeviceVerifyPolicy.Auto,
            ) { success, info, error ->
                Log.i(
                    TAG,
                    "SDK initialize callback: success=$success, " +
                        "hasTextStream=${info?.hasTextStream}, " +
                        "services=${info?.enabledServiceIds.orEmpty()}",
                )
                if (!success) {
                    Log.w(TAG, "SDK authentication failed: $error")
                }
                registerDialogLogging()
                ui {
                    sdkReady = true
                    textStreamAvailable = success && info?.hasTextStream == true
                    if (success) {
                        sdkStatus.text = getString(R.string.sdk_initialized)
                        val services = info?.enabledServiceIds
                            ?.takeIf { it.isNotEmpty() }
                            ?.joinToString()
                            ?: getString(R.string.no_services_reported)
                        capabilityStatus.text = if (textStreamAvailable) {
                            getString(R.string.text_stream_enabled, services)
                        } else {
                            getString(R.string.text_stream_missing, services)
                        }
                    } else {
                        sdkStatus.text = getString(
                            R.string.sdk_auth_failed_pcm_available,
                            error?.toString() ?: getString(R.string.unknown_error),
                        )
                        capabilityStatus.text = getString(R.string.text_stream_unavailable)
                    }
                    refreshButtons()
                }
            }
        }.onFailure { showSdkFailure(it) }
    }

    private fun registerDialogLogging() {
        VHOManager.registerDialogListener(object : OnDialogListener {
            override fun onDialogActive() {
                Log.i(TAG, "Dialog callback: onDialogActive")
            }

            override fun onDialogNum(dialogNum: String?) {
                Log.i(TAG, "Dialog callback: onDialogNum=$dialogNum")
            }

            override fun onDialogInActive() {
                Log.i(TAG, "Dialog callback: onDialogInActive")
            }
        })
        Log.i(TAG, "Dialog listener registered")
    }

    private fun showSdkFailure(error: Throwable) {
        Log.e(TAG, "SDK initialization failed", error)
        ui {
            // Keep the device path available for another connection attempt.
            sdkReady = true
            sdkStatus.text = getString(
                R.string.sdk_exception_pcm_may_work,
                error.message ?: error.javaClass.simpleName,
            )
            capabilityStatus.text = getString(R.string.text_stream_unavailable)
            refreshButtons()
        }
    }

    private fun connectHeadset() {
        if (!sdkReady) {
            toast(getString(R.string.initialize_first))
            return
        }
        if (!ensurePermissions(bluetoothPermissions(), REQUEST_BLUETOOTH) { connectHeadset() }) {
            return
        }
        connectHeadsetWithPermission()
    }

    @SuppressLint("MissingPermission")
    private fun connectHeadsetWithPermission() {
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            headsetStatus.text = getString(R.string.bluetooth_unavailable)
            return
        }
        if (!adapter.isEnabled) {
            headsetStatus.text = getString(R.string.enable_bluetooth)
            return
        }
        if (VHOManager.isConnectSPP()) {
            onHeadsetReady(null)
            return
        }

        headsetStatus.text = getString(R.string.headset_connecting)
        refreshButtons()

        val bondedHeadset = runCatching {
            adapter.bondedDevices.firstOrNull {
                it.name?.startsWith(HEADSET_NAME_PREFIX, ignoreCase = true) == true
            }
        }.getOrNull()

        if (bondedHeadset != null) {
            Log.i(TAG, "Connecting to bonded headset ${bondedHeadset.name}")
            VHOManager.connectSPP(bondedHeadset.address, connectListener)
        } else {
            Log.i(TAG, "No matching bonded device; starting SDK scan")
            val started = VHOManager.scanAndConnect(this, connectListener)
            if (!started) {
                headsetStatus.text = getString(R.string.headset_scan_failed)
                refreshButtons()
            }
        }
    }

    private val connectListener = object : OnConnectChangeListener {
        override fun onConnectChange(
            targetMac: String?,
            status: GaiaConnectStatus?,
            info: String?,
        ) {
            Log.i(TAG, "Connection status=$status info=$info")
            ui {
                when (status) {
                    GaiaConnectStatus.CONNECTING ->
                        headsetStatus.text = getString(R.string.headset_connecting)
                    GaiaConnectStatus.CONNECTED ->
                        headsetStatus.text = getString(R.string.headset_connected_preparing)
                    GaiaConnectStatus.CONNECT_ERROR ->
                        headsetStatus.text = getString(
                            R.string.headset_connection_error,
                            info ?: VHOManager.lastConnectError().orEmpty(),
                        )
                    GaiaConnectStatus.DISCONNECTED, GaiaConnectStatus.DISCONNECTING -> {
                        headsetReady = false
                        headsetStatus.text = getString(R.string.headset_disconnected)
                    }
                    else -> Unit
                }
                refreshButtons()
            }
        }

        override fun onConnectReady(targetMac: String?) {
            ui { onHeadsetReady(targetMac) }
        }
    }

    private fun onHeadsetReady(targetMac: String?) {
        headsetReady = true
        runCatching { VHOManager.initSBC() }
            .onFailure { Log.w(TAG, "initSBC failed", it) }
        headsetStatus.text = getString(
            R.string.headset_ready,
            targetMac?.takeLast(5) ?: getString(R.string.connected),
        )
        refreshButtons()
        queryPower()
    }

    private fun queryPower() {
        VHOManager.getPowerPositionConnectionOnce(object : OnResultListener<PowerEntity> {
            override fun onResult(data: PowerEntity?) {
                data ?: return
                ui {
                    val placement = when {
                        data.leftInCase && data.rightInCase ->
                            getString(R.string.both_earbuds_in_case)
                        data.leftInCase -> getString(R.string.left_earbud_in_case)
                        data.rightInCase -> getString(R.string.right_earbud_in_case)
                        else -> getString(R.string.earbuds_out_of_case)
                    }
                    headsetStatus.text = getString(
                        R.string.headset_ready_with_power,
                        data.leftPowerValue,
                        data.rightPowerValue,
                        placement,
                    )
                }
            }

            override fun onError(code: Int, msg: String?) {
                Log.w(TAG, "Power query failed: $code $msg")
            }
        })
    }

    private fun toggleRecording() {
        if (recording) {
            stopRecording()
            return
        }
        if (!headsetReady && !VHOManager.isConnectSPP()) {
            toast(getString(R.string.connect_first))
            return
        }
        if (!ensurePermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_MICROPHONE,
            ) { toggleRecording() }
        ) {
            return
        }
        startRecording()
    }

    private fun startRecording() {
        pcmFrames = 0
        pcmBytes = 0
        pcmStatus.text = getString(R.string.pcm_listening)
        partialText.text = getString(R.string.no_partial_result)
        startWavRecording()

        if (textStreamAvailable && hasCredentials) {
            Log.i(TAG, "Starting live recording with PCM + text-stream")
            VHOManager.configure(
                VHOManagerConfig(
                    credentials = credentials,
                    deviceVerifyPolicy = VHODeviceVerifyPolicy.Auto,
                    pcmSavePath = "${externalCacheDir ?: cacheDir}/earceo-live.pcm",
                    textStreamEnabled = true,
                    enableSpeakerChannel = false,
                ),
            )
            VHOManager.setRecordListener(recordListener)
        } else {
            Log.i(
                TAG,
                "Starting live recording in PCM-only mode: " +
                    "hasCredentials=$hasCredentials, textStreamAvailable=$textStreamAvailable",
            )
            // SDK contract: null disables text-stream while retaining PCM capture.
            VHOManager.configure(null)
            VHOManager.setRecordListener(null)
        }

        VHOManager.setPcmListener(object : OnResultListener<RecordDataEntity> {
            override fun onResult(data: RecordDataEntity?) {
                data ?: return
                val audioData = data.audioData
                val size = audioData?.size ?: 0
                if (audioData != null && audioData.isNotEmpty()) {
                    activeWavRecorder?.append(audioData)
                }
                pcmFrames += 1
                pcmBytes += size
                if (pcmFrames == 1L || pcmFrames % PCM_UI_UPDATE_INTERVAL == 0L || data.isEnd) {
                    ui {
                        pcmStatus.text = getString(
                            R.string.pcm_receiving,
                            pcmFrames,
                            pcmBytes,
                            data.channel,
                        )
                    }
                }
            }

            override fun onError(code: Int, msg: String?) {
                ui {
                    handlePcmFailure(code, msg)
                }
            }
        })

        runCatching {
            VHOManager.initSBC()
            VHOManager.startLiveRecord()
        }.onSuccess {
            recording = true
            recordButton.text = getString(R.string.stop_recording)
            sdkStatus.text = if (textStreamAvailable) {
                getString(R.string.recording_pcm_and_asr)
            } else {
                getString(R.string.recording_pcm_only)
            }
            refreshButtons()
        }.onFailure {
            Log.e(TAG, "Unable to start live recording", it)
            pcmStatus.text = getString(
                R.string.recording_start_failed,
                it.message ?: it.javaClass.simpleName,
            )
            VHOManager.setPcmListener(null)
            finishWavRecording()
        }
    }

    private fun stopRecording() {
        runCatching { VHOManager.stopLiveRecord() }
            .onFailure { Log.w(TAG, "Unable to stop live recording", it) }
        recording = false
        VHOManager.setPcmListener(null)
        VHOManager.setRecordListener(null)
        finishWavRecording()
        partialText.text = getString(R.string.no_partial_result)
        recordButton.text = getString(R.string.start_recording)
        sdkStatus.text = getString(R.string.recording_stopped)
        refreshButtons()
    }

    private fun handlePcmFailure(code: Int, message: String?) {
        Log.w(TAG, "PCM failure: $code $message")
        runCatching { VHOManager.stopLiveRecord() }
        recording = false
        VHOManager.setPcmListener(null)
        VHOManager.setRecordListener(null)
        finishWavRecording()
        pcmStatus.text = getString(R.string.pcm_error, code, message.orEmpty())
        sdkStatus.text = getString(R.string.recording_stopped_after_error)
        recordButton.text = getString(R.string.start_recording)
        refreshButtons()
    }

    private val recordListener = object : VHORecordListener {
        override fun onTextStreamStarted(type: VHORecordType) {
            Log.i(TAG, "Text stream started: type=$type")
            ui { capabilityStatus.text = getString(R.string.text_stream_running) }
        }

        override fun onTextStreamResult(result: VHOTextStreamResult) {
            Log.i(
                TAG,
                "Text stream result: type=${result.type}, textLength=${result.text.length}",
            )
            ui {
                when (result.type) {
                    VHOTextStreamResultType.Partial -> partialText.text = result.text
                    VHOTextStreamResultType.Final -> {
                        if (result.text.isNotBlank()) {
                            if (transcript.isNotEmpty()) transcript.append('\n')
                            transcript.append(result.text)
                            transcriptText.text = transcript.toString()
                        }
                        partialText.text = getString(R.string.no_partial_result)
                    }
                    else -> Unit
                }
            }
        }

        override fun onTextStreamEnded(type: VHORecordType, error: VHOError?) {
            if (error == null) {
                Log.i(TAG, "Text stream ended: type=$type")
            } else {
                Log.w(TAG, "Text stream ended with error: type=$type, error=$error")
            }
            ui {
                capabilityStatus.text = if (error == null) {
                    getString(R.string.text_stream_ended)
                } else {
                    getString(R.string.text_stream_ended_with_error, error.toString())
                }
            }
        }

        override fun onTextStreamStartFailed(type: VHORecordType, error: VHOError) {
            Log.w(TAG, "Text stream start failed: type=$type, error=$error")
            ui {
                textStreamAvailable = false
                capabilityStatus.text = getString(
                    R.string.text_stream_failed_pcm_continues,
                    error.toString(),
                )
            }
        }
    }

    private fun clearTranscript() {
        transcript.clear()
        partialText.text = getString(R.string.no_partial_result)
        transcriptText.text = getString(R.string.no_final_result)
    }

    private fun startWavRecording() {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val directory = externalCacheDir ?: cacheDir
        val outputFile = File(directory, "earceo-$timestamp.wav")
        activeWavRecorder = WavRecorder(outputFile, audioFileExecutor)
        wavFinalizing = false
        recordingFileStatus.text = getString(R.string.recording_file_recording, outputFile.name)
        refreshButtons()
    }

    private fun finishWavRecording(updateUi: Boolean = true) {
        val recorder = activeWavRecorder ?: return
        activeWavRecorder = null
        wavFinalizing = true
        if (updateUi && ::recordingFileStatus.isInitialized) {
            recordingFileStatus.text = getString(R.string.recording_file_finalizing)
            refreshButtons()
        }

        recorder.finish { result ->
            if (!updateUi) return@finish
            ui {
                wavFinalizing = false
                if (result.error == null && result.pcmBytes > 0L) {
                    latestWavFile = result.file
                    val seconds = result.pcmBytes.toDouble() / WavRecorder.BYTES_PER_SECOND
                    recordingFileStatus.text = getString(
                        R.string.recording_file_ready,
                        result.file.name,
                        seconds,
                        result.file.length(),
                    )
                } else {
                    val detail = result.error?.message ?: getString(R.string.recording_file_empty)
                    recordingFileStatus.text = getString(R.string.recording_file_failed, detail)
                }
                refreshButtons()
            }
        }
    }

    private fun exportLatestRecording() {
        val file = latestWavFile?.takeIf { it.isFile && it.length() > WAV_HEADER_BYTES }
        if (file == null) {
            toast(getString(R.string.no_recording_to_export))
            return
        }
        pendingExportFile = file
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/wav"
            putExtra(Intent.EXTRA_TITLE, file.name)
        }
        runCatching {
            startActivityForResult(intent, REQUEST_EXPORT_WAV)
        }.onFailure {
            pendingExportFile = null
            Log.e(TAG, "Unable to open WAV export destination", it)
            toast(
                getString(
                    R.string.recording_export_failed,
                    it.message ?: it.javaClass.simpleName,
                ),
            )
        }
    }

    private fun ensurePermissions(
        permissions: Array<String>,
        requestCode: Int,
        afterGranted: () -> Unit,
    ): Boolean {
        val missing = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return true
        pendingAfterPermission = afterGranted
        requestPermissions(missing.toTypedArray(), requestCode)
        return false
    }

    private fun bluetoothPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun refreshButtons() {
        initializeButton.isEnabled = !recording
        connectButton.isEnabled = sdkReady && !recording
        recordButton.isEnabled = (headsetReady || VHOManager.isConnectSPP()) && !wavFinalizing
        exportRecordingButton.isEnabled =
            latestWavFile?.isFile == true && !recording && !wavFinalizing
    }

    private fun actionButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 16f
            setOnClickListener { action() }
        }
    }

    private fun statusCard(initial: String): TextView {
        return text(initial, 15f, Color.rgb(43, 57, 87), false)
            .withPadding(14)
            .withMargins(bottom = 8)
            .also { it.setBackgroundColor(Color.WHITE) }
    }

    private fun sectionTitle(label: String): TextView {
        return text(label, 17f, Color.rgb(21, 31, 55), true).withMargins(top = 18, bottom = 8)
    }

    private fun text(label: String, size: Float, color: Int, bold: Boolean): TextView {
        return TextView(this).apply {
            text = label
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
    }

    private fun <T : View> T.withPadding(value: Int): T {
        setPadding(dp(value), dp(value), dp(value), dp(value))
        return this
    }

    private fun <T : View> T.withMargins(
        top: Int = 0,
        bottom: Int = 0,
    ): T {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }
        return this
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun ui(block: () -> Unit) {
        if (isFinishing || isDestroyed) return
        runOnUiThread {
            if (!isFinishing && !isDestroyed) block()
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "EarCEO"
        private const val HEADSET_NAME_PREFIX = "iFLYBUDS"
        private const val REQUEST_BLUETOOTH = 1001
        private const val REQUEST_MICROPHONE = 1002
        private const val REQUEST_EXPORT_WAV = 1003
        private const val PCM_UI_UPDATE_INTERVAL = 25L
        private const val WAV_HEADER_BYTES = 44L
    }
}
