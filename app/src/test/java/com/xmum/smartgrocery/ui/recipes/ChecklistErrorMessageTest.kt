package com.xmum.smartgrocery.ui.recipes

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class ChecklistErrorMessageTest {
    private val busy = """{"error":{"code":503,"message":"This model is currently experiencing high demand. Spikes in demand are usually temporary. Please try again later.","status":"UNAVAILABLE"}}"""

    @Test fun currentProviderErrorBecomesFriendlyBusyMessage() {
        val shown = ChecklistErrorMessage.from(busy)
        assertEquals("AI service is busy", shown.title)
        assertTrue(shown.message.contains("try again later"))
        assertTrue(shown.message.contains("request has been kept"))
        assertTrue(shown.showRetry)
        assertFalse(shown.message.contains("503")); assertFalse(shown.message.contains("UNAVAILABLE"))
    }
    @Test fun nestedAndEscapedServerDetailsHaveTheSamePresentation() {
        val envelope = Gson().toJson(mapOf("error" to "AI checklist generation failed.", "detail" to busy, "code" to 503))
        assertEquals("AI service is busy", ChecklistErrorMessage.from(envelope).title)
        assertEquals("AI service is busy", ChecklistErrorMessage.from(Gson().toJson(busy)).title)
        assertEquals("AI service is busy", ChecklistErrorMessage.from("AI recipe generation failed with HTTP 503.").title)
    }
    @Test fun usageLimitsAndTimeoutsHaveDistinctExplanations() {
        assertEquals("AI service limit reached", ChecklistErrorMessage.from("""{"code":429,"status":"RESOURCE_EXHAUSTED"}""").title)
        assertEquals("The request took too long", ChecklistErrorMessage.from("Gemini checklist generation timed out.").title)
        assertEquals("The request took too long", ChecklistErrorMessage.from("HTTP 504").title)
    }
    @Test fun connectionDetailsStayPrivateAndHavePracticalInstructions() {
        val shown = ChecklistErrorMessage.from("Failed to connect to /10.0.2.2 (port 8787)")
        assertEquals("Connection problem", shown.title)
        assertTrue(shown.message.contains("internet connection"))
        assertFalse(shown.message.contains("10.0.2.2"))
    }
    @Test fun configurationAndPaymentErrorsDoNotClaimTheModelIsBusy() {
        for (code in listOf(401, 402, 403)) {
            val shown = ChecklistErrorMessage.from("""{"code":$code,"message":"private configuration detail"}""")
            assertEquals("AI service unavailable", shown.title)
            assertFalse(shown.showRetry)
            assertFalse(shown.message.contains("private configuration detail"))
        }
    }
    @Test fun unknownMalformedAndHtmlResponsesNeverReachTheScreen() {
        for (raw in listOf("<html>private upstream stack trace</html>", "{broken-json", "API response private-token=secret123", "", null)) {
            val shown = ChecklistErrorMessage.from(raw)
            assertNotEquals(raw, shown.message)
            assertFalse(shown.message.contains("secret123"))
            assertFalse(shown.message.contains("{")); assertFalse(shown.message.contains("<html>"))
        }
    }
    @Test fun inputAndHistoryProblemsRemainActionableWithoutAProviderRetryButton() {
        val input = ChecklistErrorMessage.from("Enter a dish request first, for example: Tomato Beef Noodles for 4 people.")
        assertEquals("Enter a dish request", input.title); assertFalse(input.showRetry)
        val history = ChecklistErrorMessage.from("Couldn’t load checklist history. Reopen this screen to retry.")
        assertEquals("Checklist history unavailable", history.title); assertFalse(history.showRetry)
    }
}
