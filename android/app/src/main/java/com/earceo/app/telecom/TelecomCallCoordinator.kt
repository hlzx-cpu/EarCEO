package com.earceo.app.telecom

import android.content.Context
import android.net.Uri
import android.os.Build
import android.telecom.DisconnectCause
import androidx.annotation.RequiresApi
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallsManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

interface SystemCallController {
    suspend fun runOutgoingCall(
        displayName: String,
        address: String,
        onActiveChanged: suspend (Boolean) -> Unit,
    )

    suspend fun disconnect(): Boolean
}

/**
 * Registers one self-managed audio call with Android Telecom.
 *
 * Audio routing remains owned by Telecom/Core-Telecom. In particular this
 * class must not call AudioManager.setCommunicationDevice/startBluetoothSco.
 */
@RequiresApi(Build.VERSION_CODES.O)
class TelecomCallCoordinator(context: Context) : SystemCallController {
    private sealed interface Action {
        data object Activate : Action
        data object Hold : Action
        data class Disconnect(val cause: DisconnectCause) : Action
    }

    private val callsManager = CallsManager(context.applicationContext).apply {
        registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
    }
    private var actions: Channel<Action>? = null

    override suspend fun runOutgoingCall(
        displayName: String,
        address: String,
        onActiveChanged: suspend (Boolean) -> Unit,
    ) {
        check(actions == null) { "Only one EarCEO call can be active" }
        val actionChannel = Channel<Action>(Channel.BUFFERED)
        actions = actionChannel
        val attributes = CallAttributesCompat(
            displayName = displayName,
            address = Uri.parse(address),
            direction = CallAttributesCompat.DIRECTION_OUTGOING,
            callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
            callCapabilities = CallAttributesCompat.SUPPORTS_SET_INACTIVE,
        )

        try {
            callsManager.addCall(
                attributes,
                onAnswer = { onActiveChanged(true) },
                onDisconnect = {
                    onActiveChanged(false)
                    actionChannel.close()
                },
                onSetActive = { onActiveChanged(true) },
                onSetInactive = { onActiveChanged(false) },
            ) {
                launch {
                    when (setActive()) {
                        is CallControlResult.Success -> onActiveChanged(true)
                        is CallControlResult.Error -> error("Telecom rejected active call state")
                    }
                    actionChannel.receiveAsFlow().collect { action ->
                        when (action) {
                            Action.Activate -> setActive()
                            Action.Hold -> setInactive()
                            is Action.Disconnect -> {
                                disconnect(action.cause)
                                actionChannel.close()
                            }
                        }
                    }
                }
            }
        } finally {
            actions = null
            actionChannel.close()
            onActiveChanged(false)
        }
    }

    override suspend fun disconnect(): Boolean {
        val channel = actions ?: return false
        channel.send(Action.Disconnect(DisconnectCause(DisconnectCause.LOCAL)))
        return true
    }
}
