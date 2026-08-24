package com.megamaced.nccollectives.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The holder is what carries an SSO import back from
 * `MainActivity.onActivityResult` to the login screen. Its contract is small
 * but load-bearing: `consume()` has to actually clear, or a ViewModel
 * recreated after the import replays it against an already-signed-in session.
 */
class SsoAccountHolderTest {
    @Test
    fun `starts empty`() {
        assertNull(SsoAccountHolder().outcome.value)
    }

    @Test
    fun `an imported account survives until consumed`() {
        val holder = SsoAccountHolder()
        holder.publish(
            SsoImportOutcome.Imported(
                accountName = "bob@cloud.example.com",
                userId = "bob",
                serverUrl = "https://cloud.example.com",
            ),
        )

        val outcome = holder.outcome.value
        assertTrue(outcome is SsoImportOutcome.Imported)
        assertEquals("bob", (outcome as SsoImportOutcome.Imported).userId)

        holder.consume()
        assertNull(holder.outcome.value)
    }

    /**
     * A refused grant travels the same channel as a successful one — the SSO
     * library's own error dialog can't render on this app's theme, so silence
     * is the alternative.
     */
    @Test
    fun `a failure travels the same channel`() {
        val holder = SsoAccountHolder()
        holder.publish(SsoImportOutcome.Failed("Access was declined"))

        assertEquals(
            SsoImportOutcome.Failed("Access was declined"),
            holder.outcome.value,
        )

        holder.consume()
        assertNull(holder.outcome.value)
    }

    @Test
    fun `a later outcome replaces an unconsumed one`() {
        val holder = SsoAccountHolder()
        holder.publish(SsoImportOutcome.Failed("first"))
        holder.publish(SsoImportOutcome.Failed("second"))

        assertEquals(SsoImportOutcome.Failed("second"), holder.outcome.value)
    }
}
