package com.earceo.app

/**
 * Safe approval metadata required to render and retry one explicit decision.
 *
 * The reviewed command, transcript, backend token and repository path never
 * enter this model.
 */
data class ApprovalState(
    val sessionId: String,
    val turnId: String,
    val approvalId: String,
    val riskLevel: String,
    val title: String,
    val summary: String,
    val expiresAt: String,
    val canApprove: Boolean,
    val canReject: Boolean,
    val status: String = "pending",
    val pendingDecision: String? = null,
    val clientDecisionId: String? = null,
) {
    fun allows(decision: String): Boolean = when (decision) {
        "approve" -> canApprove
        "reject" -> canReject
        else -> false
    }
}

class ApprovalStore(
    private val preferences: RecoveryPreferences,
) {
    @Synchronized
    fun load(): ApprovalState? {
        val sessionId = required(KEY_SESSION_ID) ?: return null
        val turnId = required(KEY_TURN_ID) ?: return null
        val approvalId = required(KEY_APPROVAL_ID) ?: return null
        val riskLevel = required(KEY_RISK_LEVEL) ?: return null
        val status = required(KEY_STATUS) ?: return null
        return ApprovalState(
            sessionId = sessionId,
            turnId = turnId,
            approvalId = approvalId,
            riskLevel = riskLevel,
            title = preferences.getString(KEY_TITLE).orEmpty(),
            summary = preferences.getString(KEY_SUMMARY).orEmpty(),
            expiresAt = preferences.getString(KEY_EXPIRES_AT).orEmpty(),
            canApprove = preferences.getString(KEY_CAN_APPROVE) == "true",
            canReject = preferences.getString(KEY_CAN_REJECT) == "true",
            status = status,
            pendingDecision = preferences.getString(KEY_PENDING_DECISION)
                ?.takeIf(String::isNotBlank),
            clientDecisionId = preferences.getString(KEY_CLIENT_DECISION_ID)
                ?.takeIf(String::isNotBlank),
        )
    }

    @Synchronized
    fun save(state: ApprovalState) {
        require(state.sessionId.isNotBlank())
        require(state.turnId.isNotBlank())
        require(state.approvalId.isNotBlank())
        require(state.riskLevel in setOf("R2", "R3"))
        require(state.status.isNotBlank())
        preferences.write(
            mapOf(
                KEY_SESSION_ID to state.sessionId,
                KEY_TURN_ID to state.turnId,
                KEY_APPROVAL_ID to state.approvalId,
                KEY_RISK_LEVEL to state.riskLevel,
                KEY_TITLE to state.title,
                KEY_SUMMARY to state.summary,
                KEY_EXPIRES_AT to state.expiresAt,
                KEY_CAN_APPROVE to state.canApprove.toString(),
                KEY_CAN_REJECT to state.canReject.toString(),
                KEY_STATUS to state.status,
                KEY_PENDING_DECISION to state.pendingDecision,
                KEY_CLIENT_DECISION_ID to state.clientDecisionId,
            ),
        )
    }

    /**
     * Refresh safe server metadata while retaining an uncertain decision retry.
     */
    @Synchronized
    fun mergeFromBackend(state: ApprovalState): ApprovalState {
        val current = load()
        val merged = if (
            current?.approvalId == state.approvalId &&
            state.status == "pending"
        ) {
            state.copy(
                pendingDecision = current.pendingDecision,
                clientDecisionId = current.clientDecisionId,
            )
        } else {
            state
        }
        save(merged)
        return merged
    }

    /**
     * Allocate one stable idempotency key before the network request starts.
     */
    @Synchronized
    fun beginDecision(
        decision: String,
        idFactory: () -> String,
    ): ApprovalState {
        val current = checkNotNull(load()) { "No approval is available." }
        require(current.status == "pending")
        require(current.allows(decision))
        val existingDecision = current.pendingDecision
        if (existingDecision != null) {
            require(existingDecision == decision) {
                "A different approval decision is already pending."
            }
            return current
        }
        val decisionId = idFactory().also { require(it.isNotBlank()) }
        val updated = current.copy(
            pendingDecision = decision,
            clientDecisionId = decisionId,
        )
        save(updated)
        return updated
    }

    @Synchronized
    fun clear() {
        preferences.remove(RECOVERY_KEYS)
    }

    private fun required(key: String): String? {
        return preferences.getString(key)?.takeIf(String::isNotBlank)
    }

    companion object {
        const val KEY_SESSION_ID = "approval_session_id"
        const val KEY_TURN_ID = "approval_turn_id"
        const val KEY_APPROVAL_ID = "approval_id"
        const val KEY_RISK_LEVEL = "approval_risk_level"
        const val KEY_TITLE = "approval_title"
        const val KEY_SUMMARY = "approval_summary"
        const val KEY_EXPIRES_AT = "approval_expires_at"
        const val KEY_CAN_APPROVE = "approval_can_approve"
        const val KEY_CAN_REJECT = "approval_can_reject"
        const val KEY_STATUS = "approval_status"
        const val KEY_PENDING_DECISION = "approval_pending_decision"
        const val KEY_CLIENT_DECISION_ID = "approval_client_decision_id"

        val RECOVERY_KEYS = setOf(
            KEY_SESSION_ID,
            KEY_TURN_ID,
            KEY_APPROVAL_ID,
            KEY_RISK_LEVEL,
            KEY_TITLE,
            KEY_SUMMARY,
            KEY_EXPIRES_AT,
            KEY_CAN_APPROVE,
            KEY_CAN_REJECT,
            KEY_STATUS,
            KEY_PENDING_DECISION,
            KEY_CLIENT_DECISION_ID,
        )
    }
}
