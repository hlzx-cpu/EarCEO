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
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
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
import com.earceo.app.audio.Pcm16FrameBuffer
import com.earceo.app.audio.ViaimAudioBufferCallback
import com.earceo.app.call.CallNotificationManager
import com.earceo.app.call.CallSessionCoordinator
import com.earceo.app.call.EarCeoCallService
import com.earceo.app.rtc.LiveKitCallTransport
import com.earceo.app.rtc.LiveKitRoomCredentials
import com.earceo.app.telecom.TelecomCallCoordinator
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.sse.EventSource

/**
 * EarCEO Android headset and CEO command client.
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
    private lateinit var backendStatus: TextView
    private lateinit var callStatus: TextView
    private lateinit var backendResult: TextView
    private lateinit var partialText: TextView
    private lateinit var transcriptText: EditText
    private lateinit var initializeButton: Button
    private lateinit var connectButton: Button
    private lateinit var recordButton: Button
    private lateinit var exportRecordingButton: Button
    private lateinit var submitButton: Button
    private lateinit var discardButton: Button
    private lateinit var cancelButton: Button
    private lateinit var startCallButton: Button
    private lateinit var endCallButton: Button
    private lateinit var approvalCard: LinearLayout
    private lateinit var approvalSection: TextView
    private lateinit var approvalRisk: TextView
    private lateinit var approvalTitle: TextView
    private lateinit var approvalSummary: TextView
    private lateinit var approvalExpiry: TextView
    private lateinit var approveButton: Button
    private lateinit var rejectButton: Button

    private val commandDraft = CommandDraft()
    private val audioFileExecutor = Executors.newSingleThreadExecutor()
    private var pendingAfterPermission: (() -> Unit)? = null
    private var activeWavRecorder: WavRecorder? = null
    private var latestWavFile: File? = null
    private var pendingExportFile: File? = null
    private var currentSessionId: String? = null
    private var lastEventId: String? = null
    private var lastEventSequence = 0
    private var eventSource: EventSource? = null
    private var streamGeneration = 0
    private var backendRecoveryInProgress = false
    private var approvalDecisionInFlight = false
    private val reconnectPolicy = SseReconnectPolicy()
    private val callScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val viaimCallAudioCallback = ViaimAudioBufferCallback(Pcm16FrameBuffer())
    private var callSession: CallSessionCoordinator? = null
    @Volatile private var callViaimCaptureActive = false
    @Volatile private var callState = CallSessionCoordinator.State.IDLE

    private val apiClient: EarCeoApiClient by lazy {
        EarCeoApiClient(
            baseUrl = BuildConfig.EARCEO_BACKEND_URL,
            token = BuildConfig.EARCEO_API_TOKEN,
            projectId = BuildConfig.EARCEO_PROJECT_ID,
        )
    }

    private val clientSessionId: String by lazy {
        val preferences = getSharedPreferences("earceo-mobile", MODE_PRIVATE)
        preferences.getString("client_session_id", null)
            ?: "client-session-${UUID.randomUUID()}".also {
                preferences.edit().putString("client_session_id", it).apply()
            }
    }

    private val activeTurnStore: ActiveTurnStore by lazy {
        ActiveTurnStore(
            SharedPreferencesRecoveryPreferences(
                getSharedPreferences("earceo-mobile", MODE_PRIVATE),
            ),
        )
    }

    private val approvalStore: ApprovalStore by lazy {
        ApprovalStore(
            SharedPreferencesRecoveryPreferences(
                getSharedPreferences("earceo-mobile", MODE_PRIVATE),
            ),
        )
    }

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
        initializeCallStack()
        refreshButtons()
        initializeSdk()
        recoverBackendTask()
    }

    override fun onDestroy() {
        runCatching {
            if (recording) VHOManager.stopLiveRecord()
            VHOManager.setPcmListener(null)
            VHOManager.setRecordListener(null)
            VHOManager.release()
        }
        finishWavRecording(updateUi = false)
        closeEventStream()
        callSession?.close()
        callScope.cancel()
        apiClient.shutdown()
        audioFileExecutor.shutdown()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == CallNotificationManager.ACTION_END_CALL) endCall()
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
        backendStatus = statusCard(
            if (apiClient.configured) {
                getString(R.string.backend_ready, BuildConfig.EARCEO_PROJECT_ID)
            } else {
                getString(R.string.backend_not_configured)
            },
        )
        callStatus = statusCard(getString(R.string.call_idle))
        content.addView(sdkStatus)
        content.addView(capabilityStatus)
        content.addView(headsetStatus)
        content.addView(pcmStatus)
        content.addView(recordingFileStatus)
        content.addView(backendStatus)
        content.addView(callStatus)

        initializeButton = actionButton(getString(R.string.initialize_sdk)) { initializeSdk() }
        connectButton = actionButton(getString(R.string.connect_headset)) { connectHeadset() }
        recordButton = actionButton(getString(R.string.start_recording)) { toggleRecording() }
        exportRecordingButton = actionButton(getString(R.string.export_recording)) {
            exportLatestRecording()
        }
        startCallButton = actionButton(getString(R.string.start_livekit_call)) { startCall() }
        endCallButton = actionButton(getString(R.string.end_livekit_call)) { endCall() }
        content.addView(initializeButton.withMargins(top = 12))
        content.addView(connectButton)
        content.addView(recordButton)
        content.addView(exportRecordingButton)
        content.addView(startCallButton.withMargins(top = 12))
        content.addView(endCallButton)

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
        transcriptText = EditText(this).apply {
            hint = getString(R.string.no_final_result)
            textSize = 18f
            setTextColor(Color.rgb(25, 37, 63))
            setHintTextColor(Color.rgb(120, 130, 150))
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.WHITE)
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        content.addView(
            transcriptText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(220),
            ),
        )
        submitButton = actionButton(getString(R.string.submit_command)) { submitCommand() }
        discardButton = actionButton(getString(R.string.discard_command)) { clearTranscript() }
        cancelButton = actionButton(getString(R.string.cancel_command)) { cancelCommand() }
        content.addView(submitButton.withMargins(top = 10))
        content.addView(discardButton)
        content.addView(cancelButton)

        approvalCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.WHITE)
            visibility = View.GONE
        }
        approvalRisk = text("", 15f, Color.rgb(155, 83, 20), true)
        approvalTitle = text("", 19f, Color.rgb(25, 37, 63), true)
            .withMargins(top = 6)
        approvalSummary = text("", 16f, Color.rgb(43, 57, 87), false)
            .withMargins(top = 8)
        approvalExpiry = text("", 14f, Color.rgb(100, 110, 130), false)
            .withMargins(top = 8)
        approveButton = actionButton(getString(R.string.approval_approve)) {
            submitApprovalDecision("approve")
        }
        rejectButton = actionButton(getString(R.string.approval_reject)) {
            submitApprovalDecision("reject")
        }
        approvalCard.addView(approvalRisk)
        approvalCard.addView(approvalTitle)
        approvalCard.addView(approvalSummary)
        approvalCard.addView(approvalExpiry)
        approvalCard.addView(approveButton.withMargins(top = 10))
        approvalCard.addView(rejectButton)
        approvalSection = sectionTitle(getString(R.string.approval_section)).apply {
            visibility = View.GONE
        }
        content.addView(approvalSection)
        content.addView(approvalCard)

        content.addView(sectionTitle(getString(R.string.backend_result)))
        backendResult = text(
            getString(R.string.no_backend_result),
            16f,
            Color.rgb(43, 57, 87),
            false,
        ).withPadding(16)
        backendResult.setBackgroundColor(Color.WHITE)
        content.addView(backendResult)
        return scroll
    }

    private fun initializeCallStack() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            callStatus.text = getString(R.string.call_requires_android_o)
            return
        }
        callSession = createCallSession()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun createCallSession(): CallSessionCoordinator {
        return CallSessionCoordinator(
            scope = callScope,
            telecom = TelecomCallCoordinator(applicationContext),
            media = LiveKitCallTransport(applicationContext, viaimCallAudioCallback),
            monotonicMillis = SystemClock::elapsedRealtime,
            onError = {
                Log.e(TAG, "Call session failed", it)
            },
            onStateChanged = { state ->
                ui {
                    callState = state
                    callStatus.text = when (state) {
                        CallSessionCoordinator.State.IDLE -> getString(R.string.call_idle)
                        CallSessionCoordinator.State.CONNECTING_MEDIA ->
                            getString(R.string.call_connecting_livekit)
                        CallSessionCoordinator.State.REGISTERING_TELECOM ->
                            getString(R.string.call_registering_telecom).also {
                                startForegroundService(
                                    Intent(this, EarCeoCallService::class.java)
                                        .setAction(EarCeoCallService.ACTION_START),
                                )
                            }
                        CallSessionCoordinator.State.ACTIVE -> getString(R.string.call_active)
                        CallSessionCoordinator.State.HELD -> getString(R.string.call_held)
                        CallSessionCoordinator.State.ENDING -> getString(R.string.call_ending)
                        CallSessionCoordinator.State.FAILED -> getString(R.string.call_failed)
                    }
                    if (
                        state == CallSessionCoordinator.State.IDLE ||
                        state == CallSessionCoordinator.State.FAILED
                    ) {
                        stopService(Intent(this, EarCeoCallService::class.java))
                        stopViaimCallCapture()
                    }
                    refreshButtons()
                }
            },
        )
    }

    private fun startCall() {
        val session = callSession ?: run {
            toast(getString(R.string.call_requires_android_o))
            return
        }
        val credentials = LiveKitRoomCredentials(
            url = BuildConfig.LIVEKIT_URL,
            token = BuildConfig.LIVEKIT_TOKEN,
        )
        if (!credentials.configured) {
            toast(getString(R.string.livekit_not_configured))
            return
        }
        if (recording) {
            toast(getString(R.string.stop_recording_before_call))
            return
        }
        val callPermissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
        if (!ensurePermissions(
                callPermissions,
                REQUEST_MICROPHONE,
            ) {
                startCall()
            }
        ) {
            return
        }

        startViaimCallCaptureIfAvailable()
        session.startOutgoing(credentials)
        refreshButtons()
    }

    private fun endCall() {
        callSession?.end()
    }

    private fun startViaimCallCaptureIfAvailable() {
        val connected = headsetReady || VHOManager.isConnectSPP()
        callSession?.onViaimConnectionChanged(connected)
        if (!connected) return

        VHOManager.configure(null)
        VHOManager.setRecordListener(null)
        VHOManager.setPcmListener(object : OnResultListener<RecordDataEntity> {
            override fun onResult(data: RecordDataEntity?) {
                val audio = data?.audioData ?: return
                if (audio.isNotEmpty()) callSession?.onViaimPcm(audio)
            }

            override fun onError(code: Int, msg: String?) {
                Log.w(TAG, "Viaim call PCM failed: $code $msg")
                callSession?.onViaimConnectionChanged(false)
                stopViaimCallCapture()
            }
        })
        runCatching {
            VHOManager.initSBC()
            VHOManager.startLiveRecord()
        }.onSuccess {
            callViaimCaptureActive = true
        }.onFailure {
            Log.w(TAG, "Viaim call capture unavailable; using system microphone", it)
            VHOManager.setPcmListener(null)
            callSession?.onViaimConnectionChanged(false)
        }
    }

    private fun stopViaimCallCapture() {
        if (!callViaimCaptureActive) return
        callViaimCaptureActive = false
        callSession?.onViaimConnectionChanged(false)
        audioFileExecutor.execute {
            runCatching { VHOManager.stopLiveRecord() }
                .onFailure { Log.w(TAG, "Unable to stop Viaim call capture", it) }
            VHOManager.setPcmListener(null)
        }
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
                        callSession?.onViaimConnectionChanged(false)
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
        callSession?.onViaimConnectionChanged(true)
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
        if (commandDraft.isActive()) {
            toast(getString(R.string.command_in_progress))
            return
        }
        if (commandDraft.state == CommandDraft.State.Review &&
            transcriptText.text.toString().isNotBlank()
        ) {
            toast(getString(R.string.review_before_new_recording))
            return
        }
        commandDraft.startListening()
        transcriptText.setText("")
        backendResult.text = getString(R.string.no_backend_result)
        backendStatus.text = getString(R.string.command_listening)
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
        val draft = commandDraft.finishListening()
        backendStatus.text = if (draft.isBlank()) {
            getString(R.string.command_empty)
        } else {
            getString(R.string.command_ready_for_review)
        }
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
        val draft = commandDraft.finishListening()
        backendStatus.text = if (draft.isBlank()) {
            getString(R.string.command_empty)
        } else {
            getString(R.string.command_ready_for_review)
        }
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
                        if (commandDraft.appendFinal(result.text)) {
                            transcriptText.setText(commandDraft.text())
                            transcriptText.setSelection(transcriptText.text.length)
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

    private fun recoverBackendTask() {
        if (!apiClient.configured) return
        backendRecoveryInProgress = true
        backendStatus.text = getString(R.string.backend_recovering_session)
        refreshButtons()
        apiClient.createOrResumeSession(clientSessionId) { sessionResult ->
            sessionResult.fold(
                onSuccess = { session ->
                    apiClient.getSession(session.sessionId) { stateResult ->
                        stateResult.fold(
                            onSuccess = { state ->
                                ui { restoreSessionState(state) }
                            },
                            onFailure = ::finishRecoveryWithFailure,
                        )
                    }
                },
                onFailure = ::finishRecoveryWithFailure,
            )
        }
    }

    private fun finishRecoveryWithFailure(error: Throwable) {
        val code = (error as? EarCeoApiClient.ApiException)?.failure?.code
            ?: error.javaClass.simpleName
        Log.w(TAG, "Backend recovery failed: code=$code")
        ui {
            backendRecoveryInProgress = false
            val stored = activeTurnStore.load()
            when {
                stored?.isTerminal == true -> {
                    currentSessionId = stored.sessionId
                    commandDraft.restoreActiveTurn(stored.turnId)
                    renderTerminalResult(stored.status, null)
                }
                stored?.status in ActiveTurnState.ACTIVE_STATUSES -> {
                    currentSessionId = stored?.sessionId
                    val restoredState = if (stored?.status == "waiting_approval") {
                        CommandDraft.State.Approval
                    } else {
                        CommandDraft.State.Working
                    }
                    commandDraft.restoreActiveTurn(stored!!.turnId, restoredState)
                    lastEventId = stored.lastEventId
                    if (restoredState == CommandDraft.State.Approval) {
                        approvalStore.load()
                            ?.takeIf {
                                it.sessionId == stored.sessionId &&
                                    it.turnId == stored.turnId
                            }
                            ?.let(::renderApproval)
                    }
                    backendStatus.text = getString(
                        R.string.backend_recovery_deferred_active,
                        code,
                    )
                }
                else -> {
                    hideApproval(clearStore = true)
                    stored?.let {
                        currentSessionId = it.sessionId
                        commandDraft.restoreForRetry(it.turnId)
                    }
                    backendStatus.text = getString(R.string.backend_recovery_failed, code)
                }
            }
            refreshButtons()
        }
    }

    private fun restoreSessionState(session: EarCeoApiClient.SessionStateResponse) {
        backendRecoveryInProgress = false
        currentSessionId = session.sessionId
        val stored = activeTurnStore.load()
        if (stored != null && stored.sessionId != session.sessionId) {
            activeTurnStore.clear()
        }
        val applicableStored = stored?.takeIf { it.sessionId == session.sessionId }
        val storedTurn = applicableStored?.let { recovery ->
            session.turns.firstOrNull { it.turnId == recovery.turnId }
        }
        val activeTurn = storedTurn?.takeIf { it.isActive }
            ?: session.turns
                .filter(EarCeoApiClient.SessionTurn::isActive)
                .maxByOrNull { it.updatedAt ?: it.submittedAt.orEmpty() }

        when {
            activeTurn != null -> {
                val keepCursor = applicableStored?.turnId == activeTurn.turnId
                lastEventId = applicableStored?.lastEventId?.takeIf { keepCursor }
                lastEventSequence = 0
                activeTurnStore.save(
                    ActiveTurnState(
                        sessionId = session.sessionId,
                        turnId = activeTurn.turnId,
                        status = activeTurn.status,
                        lastEventId = lastEventId,
                    ),
                )
                val waitingApproval = activeTurn.status == "waiting_approval"
                commandDraft.restoreActiveTurn(
                    activeTurn.turnId,
                    if (waitingApproval) {
                        CommandDraft.State.Approval
                    } else {
                        CommandDraft.State.Working
                    },
                )
                if (waitingApproval) {
                    val serverApproval = session.approvals.orEmpty()
                        .firstOrNull {
                            it.isPending &&
                                it.turnId == activeTurn.turnId &&
                                (
                                    activeTurn.approvalId == null ||
                                        it.approvalId == activeTurn.approvalId
                                    )
                        }
                    val restoredApproval = serverApproval
                        ?.toRecoveryState()
                        ?.let(approvalStore::mergeFromBackend)
                        ?: approvalStore.load()?.takeIf {
                            it.sessionId == session.sessionId &&
                                it.turnId == activeTurn.turnId
                        }
                    if (restoredApproval != null) {
                        renderApproval(restoredApproval)
                    } else {
                        hideApproval(clearStore = true)
                    }
                    backendStatus.text = getString(R.string.backend_waiting_approval)
                } else {
                    hideApproval(clearStore = true)
                    backendStatus.text = getString(
                        R.string.backend_restored_active,
                        activeTurn.turnId,
                    )
                }
                backendResult.text = getString(R.string.no_backend_result)
                refreshButtons()
                openEventStream(session.sessionId)
            }
            storedTurn?.isTerminal == true -> {
                commandDraft.restoreActiveTurn(storedTurn.turnId)
                renderTerminalResult(
                    status = storedTurn.status,
                    summary = storedTurn.summary,
                )
            }
            applicableStored?.status in setOf("sending", "retry") -> {
                hideApproval(clearStore = true)
                lastEventId = applicableStored?.lastEventId
                commandDraft.restoreForRetry(applicableStored!!.turnId)
                activeTurnStore.save(applicableStored.copy(status = "retry"))
                backendStatus.text = getString(R.string.backend_restored_retry)
                backendResult.text = getString(R.string.no_backend_result)
                refreshButtons()
            }
            else -> {
                if (applicableStored != null) {
                    activeTurnStore.clear()
                }
                hideApproval(clearStore = true)
                backendStatus.text = getString(R.string.backend_ready, apiClient.projectId)
                refreshButtons()
            }
        }
    }

    private fun refreshTerminalTurn(
        sessionId: String,
        turnId: String,
        fallbackStatus: String,
    ) {
        apiClient.getSession(sessionId) { result ->
            result.fold(
                onSuccess = { session ->
                    val turn = session.turns.firstOrNull { it.turnId == turnId }
                    ui {
                        if (turn?.isTerminal == true) {
                            renderTerminalResult(turn.status, turn.summary)
                        } else if (fallbackStatus in ActiveTurnState.TERMINAL_STATUSES) {
                            renderTerminalResult(fallbackStatus, turn?.summary)
                        } else if (turn?.status == "waiting_approval") {
                            activeTurnStore.updateStatus("waiting_approval")
                            commandDraft.markWaitingApproval()
                            val serverApproval = session.approvals.orEmpty()
                                .firstOrNull {
                                    it.isPending &&
                                        it.turnId == turnId &&
                                        (
                                            turn.approvalId == null ||
                                                it.approvalId == turn.approvalId
                                            )
                                }
                            val restoredApproval = serverApproval
                                ?.toRecoveryState()
                                ?.let(approvalStore::mergeFromBackend)
                                ?: approvalStore.load()?.takeIf {
                                    it.sessionId == sessionId && it.turnId == turnId
                                }
                            if (restoredApproval != null) {
                                renderApproval(restoredApproval)
                            } else {
                                hideApproval(clearStore = true)
                            }
                            backendStatus.text = getString(R.string.backend_waiting_approval)
                            openEventStream(sessionId)
                            refreshButtons()
                        } else {
                            hideApproval(clearStore = true)
                            activeTurnStore.updateStatus(turn?.status ?: fallbackStatus)
                            commandDraft.markWorking()
                            openEventStream(sessionId)
                            refreshButtons()
                        }
                    }
                },
                onFailure = { error ->
                    if (fallbackStatus in ActiveTurnState.TERMINAL_STATUSES) {
                        ui { renderTerminalResult(fallbackStatus, null) }
                    } else {
                        handleBackendFailure(error)
                    }
                },
            )
        }
    }

    private fun renderApproval(approval: ApprovalState) {
        approvalSection.visibility = View.VISIBLE
        approvalCard.visibility = View.VISIBLE
        approvalRisk.text = getString(
            R.string.approval_risk,
            approval.riskLevel,
        )
        approvalTitle.text = approval.title
        approvalSummary.text = approval.summary
        approvalExpiry.text = getString(
            R.string.approval_expires,
            approval.expiresAt,
        )
        refreshApprovalButtons()
    }

    private fun hideApproval(clearStore: Boolean) {
        approvalDecisionInFlight = false
        if (clearStore) {
            approvalStore.clear()
        }
        if (::approvalCard.isInitialized) {
            approvalCard.visibility = View.GONE
            approvalSection.visibility = View.GONE
        }
    }

    private fun refreshApprovalButtons() {
        if (!::approveButton.isInitialized) return
        val approval = approvalStore.load()
        val pendingDecision = approval?.pendingDecision
        approveButton.visibility = if (approval?.canApprove == true) {
            View.VISIBLE
        } else {
            View.GONE
        }
        rejectButton.visibility = if (approval?.canReject == true) {
            View.VISIBLE
        } else {
            View.GONE
        }
        approveButton.isEnabled =
            approval?.status == "pending" &&
            !approvalDecisionInFlight &&
            (pendingDecision == null || pendingDecision == "approve")
        rejectButton.isEnabled =
            approval?.status == "pending" &&
            !approvalDecisionInFlight &&
            (pendingDecision == null || pendingDecision == "reject")
    }

    private fun approvalFromEvent(
        event: EarCeoApiClient.MobileEvent,
    ): ApprovalState? {
        val turnId = event.turnId ?: return null
        val data = event.data
        val choices = data.getAsJsonArray("choices")
            ?.mapNotNull { choice -> choice.takeIf { it.isJsonPrimitive }?.asString }
            .orEmpty()
        val approvalId = data.get("approval_id")?.asString.orEmpty()
        val riskLevel = data.get("risk_level")?.asString.orEmpty()
        if (
            approvalId.isBlank() ||
            riskLevel !in setOf("R2", "R3") ||
            choices.isEmpty()
        ) {
            return null
        }
        return ApprovalState(
            sessionId = event.sessionId,
            turnId = turnId,
            approvalId = approvalId,
            riskLevel = riskLevel,
            title = data.get("title")?.asString.orEmpty(),
            summary = data.get("summary")?.asString.orEmpty(),
            expiresAt = data.get("expires_at")?.asString.orEmpty(),
            canApprove = "approve" in choices,
            canReject = "reject" in choices,
            status = data.get("status")?.asString ?: "pending",
        )
    }

    private fun submitApprovalDecision(decision: String) {
        val approval = approvalStore.load()
        if (approval == null || approval.status != "pending" || !approval.allows(decision)) {
            toast(getString(R.string.approval_not_available))
            return
        }
        val prepared = runCatching {
            approvalStore.beginDecision(decision) {
                "decision-${UUID.randomUUID()}"
            }
        }.getOrElse {
            toast(getString(R.string.approval_different_decision_pending))
            return
        }
        val decisionId = prepared.clientDecisionId ?: return
        approvalDecisionInFlight = true
        backendStatus.text = getString(
            R.string.approval_sending,
            if (decision == "approve") {
                getString(R.string.approval_approve)
            } else {
                getString(R.string.approval_reject)
            },
        )
        renderApproval(prepared)
        apiClient.decideApproval(
            sessionId = prepared.sessionId,
            approvalId = prepared.approvalId,
            decisionId = decisionId,
            decision = decision,
        ) { result ->
            result.fold(
                onSuccess = { response ->
                    ui { handleApprovalDecisionSuccess(prepared, response) }
                },
                onFailure = { error ->
                    val failure = (error as? EarCeoApiClient.ApiException)?.failure
                    val code = failure?.code
                        ?: error.javaClass.simpleName
                    Log.w(TAG, "Approval decision failed: code=$code")
                    ui {
                        approvalDecisionInFlight = false
                        if (failure?.retryable == false) {
                            backendStatus.text = getString(
                                R.string.approval_refreshing,
                                code,
                            )
                            refreshTerminalTurn(
                                prepared.sessionId,
                                prepared.turnId,
                                "waiting_approval",
                            )
                            return@ui
                        }
                        backendStatus.text = getString(
                            R.string.approval_request_failed,
                            code,
                        )
                        approvalStore.load()?.let(::renderApproval)
                        refreshButtons()
                    }
                },
            )
        }
    }

    private fun handleApprovalDecisionSuccess(
        approval: ApprovalState,
        response: EarCeoApiClient.ApprovalDecisionResponse,
    ) {
        approvalDecisionInFlight = false
        if (
            response.approvalId != approval.approvalId ||
            response.turnId != approval.turnId
        ) {
            backendStatus.text = getString(
                R.string.approval_request_failed,
                "INVALID_RESPONSE",
            )
            approvalStore.load()?.let(::renderApproval)
            refreshButtons()
            return
        }
        hideApproval(clearStore = true)
        activeTurnStore.updateStatus(response.turnStatus)
        if (response.turnStatus in ActiveTurnState.TERMINAL_STATUSES) {
            refreshTerminalTurn(
                approval.sessionId,
                approval.turnId,
                response.turnStatus,
            )
            return
        }
        commandDraft.markWorking()
        backendStatus.text = getString(R.string.backend_command_accepted)
        openEventStream(approval.sessionId)
        refreshButtons()
    }

    private fun renderTerminalResult(
        status: String,
        summary: String?,
        code: String? = null,
    ) {
        activeTurnStore.updateStatus(status)
        commandDraft.markResult()
        when (status) {
            "completed" -> {
                backendStatus.text = getString(R.string.backend_completed)
                backendResult.text = summary ?: getString(R.string.backend_completed)
            }
            "cancelled" -> {
                backendStatus.text = getString(R.string.backend_cancelled)
                backendResult.text = summary ?: getString(R.string.backend_cancelled_result)
            }
            else -> {
                backendStatus.text = getString(
                    R.string.backend_failed,
                    code ?: "UNKNOWN",
                )
                backendResult.text = summary ?: getString(R.string.backend_failed_generic)
            }
        }
        closeEventStream()
        hideApproval(clearStore = true)
        activeTurnStore.clearAfterTerminal(status)
        refreshButtons()
    }

    private fun closeEventStream() {
        streamGeneration += 1
        eventSource?.cancel()
        eventSource = null
        reconnectPolicy.reset()
    }

    private fun clearTranscript() {
        if (commandDraft.isActive()) {
            toast(getString(R.string.cancel_active_command_first))
            return
        }
        commandDraft.discard()
        activeTurnStore.clear()
        hideApproval(clearStore = true)
        closeEventStream()
        lastEventId = null
        lastEventSequence = 0
        partialText.text = getString(R.string.no_partial_result)
        transcriptText.setText("")
        backendStatus.text = if (apiClient.configured) {
            getString(R.string.backend_ready, apiClient.projectId)
        } else {
            getString(R.string.backend_not_configured)
        }
        backendResult.text = getString(R.string.no_backend_result)
        refreshButtons()
    }

    private fun submitCommand() {
        if (recording) {
            toast(getString(R.string.stop_before_submit))
            return
        }
        if (!apiClient.configured) {
            toast(getString(R.string.backend_not_configured))
            return
        }
        if (commandDraft.isActive()) return

        val commandText = transcriptText.text.toString().trim()
        if (commandText.isBlank()) {
            toast(getString(R.string.command_empty))
            return
        }
        commandDraft.replaceForReview(commandText)
        val turnId = commandDraft.ensureTurnId()
        commandDraft.markSending()
        hideApproval(clearStore = true)
        backendStatus.text = getString(R.string.backend_creating_session)
        backendResult.text = getString(R.string.no_backend_result)
        refreshButtons()

        apiClient.createOrResumeSession(clientSessionId) { sessionResult ->
            sessionResult.fold(
                onSuccess = { session ->
                    currentSessionId = session.sessionId
                    lastEventId = null
                    lastEventSequence = 0
                    activeTurnStore.save(
                        ActiveTurnState(
                            sessionId = session.sessionId,
                            turnId = turnId,
                            status = "sending",
                        ),
                    )
                    submitTurn(session.sessionId, turnId, commandText)
                },
                onFailure = ::handleBackendFailure,
            )
        }
    }

    private fun submitTurn(sessionId: String, turnId: String, commandText: String) {
        ui { backendStatus.text = getString(R.string.backend_sending_command) }
        apiClient.submitTurn(
            sessionId = sessionId,
            turnId = turnId,
            commandText = commandText,
            wavCaptured = latestWavFile?.isFile == true,
        ) { turnResult ->
            turnResult.fold(
                onSuccess = { turn ->
                    if (turn.status in ActiveTurnState.TERMINAL_STATUSES) {
                        refreshTerminalTurn(sessionId, turnId, turn.status)
                        return@fold
                    }
                    activeTurnStore.updateStatus(turn.status)
                    ui {
                        if (turn.status == "waiting_approval") {
                            commandDraft.markWaitingApproval()
                            backendStatus.text =
                                getString(R.string.backend_waiting_approval)
                        } else {
                            commandDraft.markWorking()
                            backendStatus.text =
                                getString(R.string.backend_command_accepted)
                        }
                        refreshButtons()
                    }
                    openEventStream(sessionId)
                },
                onFailure = ::handleBackendFailure,
            )
        }
    }

    private fun openEventStream(sessionId: String) {
        val generation = ++streamGeneration
        eventSource?.cancel()
        eventSource = apiClient.streamEvents(
            sessionId = sessionId,
            lastEventId = lastEventId,
            onOpen = {
                if (generation == streamGeneration) {
                    reconnectPolicy.reset()
                }
            },
            onEvent = { event ->
                if (generation != streamGeneration ||
                    event.sessionId != sessionId ||
                    event.eventId.isBlank() ||
                    event.sequence <= 0 ||
                    event.sequence <= lastEventSequence
                ) {
                    return@streamEvents
                }
                if (!activeTurnStore.advanceCursor(event.eventId)) {
                    return@streamEvents
                }
                lastEventId = event.eventId
                lastEventSequence = event.sequence
                reconnectPolicy.reset()
                ui {
                    val activeTurnId = activeTurnStore.load()?.turnId
                        ?: commandDraft.turnId
                    if (event.turnId == null || event.turnId == activeTurnId) {
                        handleBackendEvent(event)
                    }
                }
            },
            onFailure = { failure ->
                ui {
                    if (generation != streamGeneration || !commandDraft.isActive()) {
                        return@ui
                    }
                    if (failure.retryable) {
                        val delayMs = reconnectPolicy.nextDelayMs()
                        backendStatus.text = getString(
                            R.string.backend_reconnecting,
                            failure.code,
                            delayMs / 1_000.0,
                        )
                        window.decorView.postDelayed(
                            {
                                if (generation == streamGeneration &&
                                    commandDraft.isActive() &&
                                    !isFinishing
                                ) {
                                    openEventStream(sessionId)
                                }
                            }, delayMs,
                        )
                    } else {
                        backendStatus.text = getString(
                            R.string.backend_stream_stopped,
                            failure.code,
                        )
                    }
                }
            },
        )
    }

    private fun handleBackendEvent(event: EarCeoApiClient.MobileEvent) {
        when (event.type) {
            "turn.accepted" -> {
                val status = event.data.get("status")?.asString ?: "accepted"
                activeTurnStore.updateStatus(status)
                if (status == "waiting_approval") {
                    commandDraft.markWaitingApproval()
                    backendStatus.text = getString(R.string.backend_waiting_approval)
                } else {
                    hideApproval(clearStore = true)
                    commandDraft.markWorking()
                    backendStatus.text = getString(R.string.backend_command_accepted)
                }
            }
            "approval.required" -> {
                val approval = approvalFromEvent(event)
                if (approval != null) {
                    val persisted = approvalStore.mergeFromBackend(approval)
                    activeTurnStore.updateStatus("waiting_approval")
                    commandDraft.markWaitingApproval()
                    renderApproval(persisted)
                    backendStatus.text = getString(R.string.backend_waiting_approval)
                } else {
                    backendStatus.text = getString(
                        R.string.backend_stream_stopped,
                        "INVALID_APPROVAL_EVENT",
                    )
                }
            }
            "approval.resolved" -> {
                val resolution = event.data.get("status")?.asString.orEmpty()
                hideApproval(clearStore = true)
                when (resolution) {
                    "approved" -> {
                        activeTurnStore.updateStatus("accepted")
                        commandDraft.markWorking()
                        backendStatus.text =
                            getString(R.string.backend_command_accepted)
                    }
                    "rejected" -> refreshTerminalTurn(
                        event.sessionId,
                        event.turnId ?: return,
                        "cancelled",
                    )
                    "expired" -> refreshTerminalTurn(
                        event.sessionId,
                        event.turnId ?: return,
                        "failed",
                    )
                }
            }
            "task.progress" -> {
                hideApproval(clearStore = true)
                activeTurnStore.updateStatus("working")
                commandDraft.markWorking()
                val message = event.data.get("message")?.asString.orEmpty()
                val progress = event.data.get("progress_pct")?.asInt ?: 0
                backendStatus.text = getString(
                    R.string.backend_progress,
                    progress,
                    message,
                )
            }
            "task.completed" -> {
                renderTerminalResult(
                    status = "completed",
                    summary = event.data.get("summary")?.asString,
                )
            }
            "task.failed" -> {
                val status = event.data.get("status")?.asString
                    ?.takeIf { it in ActiveTurnState.TERMINAL_STATUSES }
                    ?: "failed"
                renderTerminalResult(
                    status = status,
                    summary = event.data.get("message")?.asString,
                    code = event.data.get("code")?.asString,
                )
            }
        }
        refreshButtons()
    }

    private fun handleBackendFailure(error: Throwable) {
        val code = (error as? EarCeoApiClient.ApiException)?.failure?.code
            ?: error.javaClass.simpleName
        Log.w(TAG, "Backend request failed: code=$code")
        ui {
            if (commandDraft.state == CommandDraft.State.Sending) {
                commandDraft.returnToReview()
            }
            backendStatus.text = getString(R.string.backend_request_failed, code)
            refreshButtons()
        }
    }

    private fun cancelCommand() {
        val sessionId = currentSessionId
        val turnId = commandDraft.turnId
        if (sessionId == null || turnId == null || !commandDraft.isActive()) {
            toast(getString(R.string.no_active_command))
            return
        }
        cancelButton.isEnabled = false
        apiClient.cancelTurn(sessionId, turnId) { result ->
            result.fold(
                onSuccess = { turn ->
                    refreshTerminalTurn(sessionId, turnId, turn.status)
                },
                onFailure = ::handleBackendFailure,
            )
        }
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
        val commandActive = commandDraft.isActive()
        val callIdle = callState == CallSessionCoordinator.State.IDLE ||
            callState == CallSessionCoordinator.State.FAILED
        initializeButton.isEnabled = !recording && !commandActive
        connectButton.isEnabled = sdkReady && !recording && !commandActive && callIdle
        recordButton.isEnabled =
            (headsetReady || VHOManager.isConnectSPP()) &&
            !wavFinalizing &&
            !commandActive &&
            callIdle
        exportRecordingButton.isEnabled =
            latestWavFile?.isFile == true && !recording && !wavFinalizing
        if (::startCallButton.isInitialized) {
            startCallButton.isEnabled =
                callSession != null && callIdle && !recording && !commandActive
            endCallButton.isEnabled = !callIdle
        }
        if (::submitButton.isInitialized) {
            submitButton.isEnabled = apiClient.configured &&
                !backendRecoveryInProgress &&
                !recording &&
                !commandActive
            discardButton.isEnabled = !backendRecoveryInProgress && !recording && !commandActive
            cancelButton.isEnabled = commandActive && currentSessionId != null
            transcriptText.isEnabled = !recording && !commandActive
        }
        refreshApprovalButtons()
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
