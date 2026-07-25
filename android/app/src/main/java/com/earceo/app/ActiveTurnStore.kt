package com.earceo.app

import android.content.SharedPreferences

/**
 * The minimum non-sensitive state required to reconnect to a backend turn.
 *
 * Command text, transcript text, credentials and results deliberately do not
 * belong in this model.
 */
data class ActiveTurnState(
    val sessionId: String,
    val turnId: String,
    val status: String,
    val lastEventId: String? = null,
) {
    val isTerminal: Boolean
        get() = status in TERMINAL_STATUSES

    companion object {
        val ACTIVE_STATUSES = setOf(
            "sending",
            "pending",
            "accepted",
            "running",
            "working",
            "waiting_approval",
        )
        val TERMINAL_STATUSES = setOf("completed", "failed", "cancelled")
    }
}

interface RecoveryPreferences {
    fun getString(key: String): String?
    fun write(values: Map<String, String?>)
    fun remove(keys: Set<String>)
}

class SharedPreferencesRecoveryPreferences(
    private val preferences: SharedPreferences,
) : RecoveryPreferences {
    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun write(values: Map<String, String?>) {
        preferences.edit().apply {
            values.forEach { (key, value) ->
                if (value == null) remove(key) else putString(key, value)
            }
        }.commit()
    }

    override fun remove(keys: Set<String>) {
        preferences.edit().apply {
            keys.forEach(::remove)
        }.commit()
    }
}

class ActiveTurnStore(
    private val preferences: RecoveryPreferences,
) {
    @Synchronized
    fun load(): ActiveTurnState? {
        val sessionId = preferences.getString(KEY_SESSION_ID)
            ?.takeIf(String::isNotBlank)
            ?: return null
        val turnId = preferences.getString(KEY_TURN_ID)
            ?.takeIf(String::isNotBlank)
            ?: return null
        val status = preferences.getString(KEY_STATUS)
            ?.takeIf(String::isNotBlank)
            ?: return null
        return ActiveTurnState(
            sessionId = sessionId,
            turnId = turnId,
            status = status,
            lastEventId = preferences.getString(KEY_LAST_EVENT_ID)
                ?.takeIf(String::isNotBlank),
        )
    }

    @Synchronized
    fun save(state: ActiveTurnState) {
        require(state.sessionId.isNotBlank())
        require(state.turnId.isNotBlank())
        require(state.status.isNotBlank())
        preferences.write(
            mapOf(
                KEY_SESSION_ID to state.sessionId,
                KEY_TURN_ID to state.turnId,
                KEY_STATUS to state.status,
                KEY_LAST_EVENT_ID to state.lastEventId,
            ),
        )
    }

    @Synchronized
    fun updateStatus(status: String): ActiveTurnState? {
        if (status.isBlank()) return load()
        val updated = load()?.copy(status = status) ?: return null
        save(updated)
        return updated
    }

    /**
     * Atomically advances the replay cursor. An exact redelivery is ignored so
     * it cannot trigger duplicate UI work or rewrite the cursor.
     */
    @Synchronized
    fun advanceCursor(eventId: String): Boolean {
        if (eventId.isBlank()) return false
        val current = load() ?: return false
        if (current.lastEventId == eventId) return false
        save(current.copy(lastEventId = eventId))
        return true
    }

    @Synchronized
    fun clearAfterTerminal(status: String): Boolean {
        if (status !in ActiveTurnState.TERMINAL_STATUSES) return false
        clear()
        return true
    }

    @Synchronized
    fun clear() {
        preferences.remove(RECOVERY_KEYS)
    }

    companion object {
        const val KEY_SESSION_ID = "active_turn_session_id"
        const val KEY_TURN_ID = "active_turn_id"
        const val KEY_STATUS = "active_turn_status"
        const val KEY_LAST_EVENT_ID = "active_turn_last_event_id"

        val RECOVERY_KEYS = setOf(
            KEY_SESSION_ID,
            KEY_TURN_ID,
            KEY_STATUS,
            KEY_LAST_EVENT_ID,
        )
    }
}
