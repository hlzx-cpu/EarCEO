package com.earceo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalStoreTest {
    @Test
    fun persistsOnlySafeApprovalMetadata() {
        val preferences = FakeRecoveryPreferences()
        val store = ApprovalStore(preferences)

        store.save(pendingApproval())

        assertEquals(pendingApproval(), store.load())
        assertTrue(ApprovalStore.RECOVERY_KEYS.containsAll(preferences.values.keys))
        assertTrue(
            preferences.values.keys.containsAll(
                ApprovalStore.RECOVERY_KEYS - setOf(
                    ApprovalStore.KEY_PENDING_DECISION,
                    ApprovalStore.KEY_CLIENT_DECISION_ID,
                ),
            ),
        )
        assertFalse(preferences.values.keys.any { it.contains("transcript", ignoreCase = true) })
        assertFalse(preferences.values.keys.any { it.contains("token", ignoreCase = true) })
        assertFalse(preferences.values.values.any { it == "sensitive reviewed command" })
    }

    @Test
    fun decisionIdIsStableAcrossRetryAndBackendRefresh() {
        val store = ApprovalStore(FakeRecoveryPreferences())
        store.save(pendingApproval())

        val first = store.beginDecision("approve") { "decision-stable" }
        val retry = store.beginDecision("approve") { "decision-new" }
        val refreshed = store.mergeFromBackend(
            pendingApproval().copy(title = "Updated safe title"),
        )

        assertEquals("decision-stable", first.clientDecisionId)
        assertEquals("decision-stable", retry.clientDecisionId)
        assertEquals("decision-stable", refreshed.clientDecisionId)
        assertEquals("approve", refreshed.pendingDecision)
        assertEquals("Updated safe title", refreshed.title)
    }

    @Test
    fun cannotChangeAnUncertainDecision() {
        val store = ApprovalStore(FakeRecoveryPreferences())
        store.save(pendingApproval())
        store.beginDecision("approve") { "decision-stable" }

        assertThrows(IllegalArgumentException::class.java) {
            store.beginDecision("reject") { "decision-other" }
        }
    }

    @Test
    fun r3NeverAllowsApproveAndClearRemovesRecoveryState() {
        val store = ApprovalStore(FakeRecoveryPreferences())
        store.save(
            pendingApproval().copy(
                riskLevel = "R3",
                canApprove = false,
                canReject = true,
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            store.beginDecision("approve") { "decision-forbidden" }
        }
        assertTrue(store.load()?.allows("reject") == true)

        store.clear()

        assertNull(store.load())
    }

    private fun pendingApproval(): ApprovalState {
        return ApprovalState(
            sessionId = "ses-1",
            turnId = "turn-1",
            approvalId = "apr-1",
            riskLevel = "R2",
            title = "Approve test deployment?",
            summary = "Safe summary",
            expiresAt = "2026-07-25T10:10:00Z",
            canApprove = true,
            canReject = true,
        )
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
