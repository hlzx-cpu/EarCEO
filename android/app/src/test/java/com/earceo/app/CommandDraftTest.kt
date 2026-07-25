package com.earceo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandDraftTest {
    @Test
    fun aggregatesFinalSentencesAndDeduplicatesAdjacentDelivery() {
        val draft = CommandDraft()
        draft.startListening()

        assertTrue(draft.appendFinal("请检查项目。"))
        assertFalse(draft.appendFinal("请检查项目。"))
        assertTrue(draft.appendFinal("再增加健康检查接口。"))

        assertEquals(
            "请检查项目。\n再增加健康检查接口。",
            draft.finishListening(),
        )
        assertEquals(CommandDraft.State.Review, draft.state)
    }

    @Test
    fun retryKeepsTurnIdButNewRecordingGetsAnotherId() {
        val draft = CommandDraft()
        draft.startListening()
        draft.appendFinal("first")
        draft.finishListening()
        val firstId = draft.ensureTurnId()
        draft.markSending()
        draft.returnToReview()

        assertEquals(firstId, draft.ensureTurnId())

        draft.startListening()
        draft.appendFinal("second")
        draft.finishListening()
        assertNotEquals(firstId, draft.ensureTurnId())
    }

    @Test
    fun recreationRestoresStableTurnIdWithoutTranscript() {
        val recreated = CommandDraft()

        recreated.restoreActiveTurn("turn-stable")

        assertEquals("turn-stable", recreated.ensureTurnId())
        assertEquals(CommandDraft.State.Working, recreated.state)
        assertEquals("", recreated.text())
    }

    @Test
    fun uncertainSubmissionRestoresSameTurnIdForManualRetryWithoutTranscript() {
        val recreated = CommandDraft()

        recreated.restoreForRetry("turn-uncertain")
        recreated.replaceForReview("re-entered command")

        assertEquals("turn-uncertain", recreated.ensureTurnId())
        assertEquals(CommandDraft.State.Review, recreated.state)
    }
}
