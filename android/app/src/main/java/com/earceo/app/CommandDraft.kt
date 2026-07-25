package com.earceo.app

import java.util.UUID

/**
 * One deliberate CEO command assembled from sentence-level Final callbacks.
 *
 * Partial text never enters this model. Repeated adjacent Final callbacks are
 * ignored because the vendor stream may redeliver the last sentence.
 */
class CommandDraft {
    enum class State {
        Idle,
        Listening,
        Review,
        Sending,
        Working,
        Result,
    }

    private val sentences = mutableListOf<String>()

    var state: State = State.Idle
        private set

    var turnId: String? = null
        private set

    fun startListening() {
        sentences.clear()
        turnId = null
        state = State.Listening
    }

    fun appendFinal(rawText: String): Boolean {
        val sentence = rawText.trim()
        if (sentence.isEmpty() || sentence == sentences.lastOrNull()) return false
        sentences += sentence
        return true
    }

    fun finishListening(): String {
        state = State.Review
        return text()
    }

    fun replaceForReview(editedText: String) {
        sentences.clear()
        editedText.trim()
            .takeIf { it.isNotEmpty() }
            ?.let(sentences::add)
        state = State.Review
    }

    fun ensureTurnId(): String {
        val existing = turnId
        if (existing != null) return existing
        return "turn-${UUID.randomUUID()}"
            .also { turnId = it }
    }

    fun markSending() {
        state = State.Sending
    }

    fun markWorking() {
        state = State.Working
    }

    fun markResult() {
        state = State.Result
    }

    fun returnToReview() {
        state = State.Review
    }

    fun discard() {
        sentences.clear()
        turnId = null
        state = State.Idle
    }

    fun text(): String = sentences.joinToString("\n")

    fun isActive(): Boolean = state == State.Sending || state == State.Working
}
