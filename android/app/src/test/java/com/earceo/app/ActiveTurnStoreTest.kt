package com.earceo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveTurnStoreTest {
    @Test
    fun serializesOnlyNonSensitiveRecoveryState() {
        val preferences = FakeRecoveryPreferences()
        val store = ActiveTurnStore(preferences)

        store.save(
            ActiveTurnState(
                sessionId = "ses-1",
                turnId = "turn-1",
                status = "working",
                lastEventId = "evt-7",
            ),
        )

        assertEquals(
            ActiveTurnState("ses-1", "turn-1", "working", "evt-7"),
            store.load(),
        )
        assertEquals(ActiveTurnStore.RECOVERY_KEYS, preferences.values.keys)
        assertFalse(preferences.values.keys.any { it.contains("transcript", ignoreCase = true) })
        assertFalse(preferences.values.keys.any { it.contains("token", ignoreCase = true) })
        assertFalse(preferences.values.values.any { it == "sensitive command text" })
    }

    @Test
    fun advancesCursorAndIgnoresExactRedelivery() {
        val store = ActiveTurnStore(FakeRecoveryPreferences())
        store.save(ActiveTurnState("ses-1", "turn-1", "working", "evt-1"))

        assertTrue(store.advanceCursor("evt-2"))
        assertEquals("evt-2", store.load()?.lastEventId)
        assertFalse(store.advanceCursor("evt-2"))
        assertEquals("evt-2", store.load()?.lastEventId)
        assertFalse(store.advanceCursor(""))
        assertEquals("evt-2", store.load()?.lastEventId)
    }

    @Test
    fun clearsOnlyAfterTerminalStatus() {
        val store = ActiveTurnStore(FakeRecoveryPreferences())
        store.save(ActiveTurnState("ses-1", "turn-1", "working", "evt-2"))

        assertFalse(store.clearAfterTerminal("working"))
        assertEquals("turn-1", store.load()?.turnId)
        assertTrue(store.clearAfterTerminal("completed"))
        assertNull(store.load())
    }

    private class FakeRecoveryPreferences : RecoveryPreferences {
        val values = mutableMapOf<String, String>()

        override fun getString(key: String): String? = values[key]

        override fun write(values: Map<String, String?>) {
            values.forEach { (key, value) ->
                if (value == null) this.values.remove(key) else this.values[key] = value
            }
        }

        override fun remove(keys: Set<String>) {
            keys.forEach(values::remove)
        }
    }
}
