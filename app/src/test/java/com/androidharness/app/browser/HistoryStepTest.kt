package com.androidharness.app.browser

import com.androidharness.app.browser.HistoryStepDecision.DONE
import com.androidharness.app.browser.HistoryStepDecision.GIVE_UP
import com.androidharness.app.browser.HistoryStepDecision.RETRY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The history-step decision, from the on-device failure of 2026-09-17.
 *
 * Fixture: `Apage.html?X` links to `Bpage.html`, which links back. The battery
 * did navigate A → click link → back and landed on an entry OLDER than A, three
 * trials out of three, while the navigate → navigate → back control passed.
 *
 * Two defects produced that, and both are pinned here:
 *  - the landing check compared URLs with the query stripped, so every
 *    `Apage.html?...` counted as the same document and the step looked like it
 *    had failed to move;
 *  - the check ran before the step's own load had settled, and each retry then
 *    consumed another history entry.
 */
class HistoryStepTest {

    private val a2 = "http://localhost:8790/Apage.html?fix=A2"
    private val aCtl = "http://localhost:8790/Apage.html?fix=ctl1"
    private val b = "http://localhost:8790/Bpage.html"

    @Test
    fun `a query string distinguishes two history entries`() {
        assertFalse(sameDocument(a2, aCtl))
        assertFalse(sameDocument(a2, "http://localhost:8790/Apage.html"))
        // A fragment is part of the entry too.
        assertFalse(sameDocument(a2, "$a2#section"))
    }

    @Test
    fun `the same document is still recognised across cosmetic differences`() {
        assertTrue(sameDocument(a2, a2))
        assertTrue(sameDocument(a2, "$a2/"))
        assertTrue(sameDocument(a2, "http://localhost:8790/Apage.html?fix=A2?"))
        assertTrue(sameDocument("HTTP://LOCALHOST:8790/Apage.html?fix=A2", a2))
        assertTrue(sameDocument("  $a2  ", a2))
    }

    /**
     * The reported failure, as a decision: the step landed on A2 (a different
     * query from the page it started on, ctl1) and its load finished. That is a
     * completed step, not a bounce, so the loop must stop instead of walking
     * further back.
     */
    @Test
    fun `a step onto the same path with a different query is done not a retry`() {
        assertEquals(
            DONE,
            decideHistoryStep(
                landedUrl = a2,
                startUrl = aCtl,
                settled = true,
                canStepFurther = true,
                attempts = 1,
                maxAttempts = 5,
            ),
        )
    }

    /**
     * The same call under the OLD comparison returned RETRY, which is what ate
     * the extra entry. Guard the specific pair so the regression cannot return.
     */
    @Test
    fun `the old query-stripping comparison would have retried the same case`() {
        val oldComparisonSaidSame = a2.substringBefore('?') == aCtl.substringBefore('?')
        assertTrue("this pair is what the old code collapsed", oldComparisonSaidSame)
        assertFalse("the new comparison must keep them apart", sameDocument(a2, aCtl))
    }

    /**
     * An unsettled landing proves nothing: the URL read can still be the
     * PREVIOUS document's. Stepping again here is precisely how an entry gets
     * skipped, so the loop gives up instead.
     */
    @Test
    fun `an unsettled landing on the starting page gives up instead of stepping again`() {
        assertEquals(
            GIVE_UP,
            decideHistoryStep(aCtl, aCtl, settled = false, canStepFurther = true, attempts = 1, maxAttempts = 5),
        )
    }

    @Test
    fun `a settled landing back on the starting page is a redirect bounce`() {
        assertEquals(
            RETRY,
            decideHistoryStep(aCtl, aCtl, settled = true, canStepFurther = true, attempts = 1, maxAttempts = 5),
        )
    }

    @Test
    fun `a bounce stops at the attempt limit`() {
        assertEquals(
            GIVE_UP,
            decideHistoryStep(aCtl, aCtl, settled = true, canStepFurther = true, attempts = 5, maxAttempts = 5),
        )
    }

    @Test
    fun `no history left to step through ends the loop`() {
        assertEquals(
            GIVE_UP,
            decideHistoryStep(aCtl, aCtl, settled = true, canStepFurther = false, attempts = 1, maxAttempts = 5),
        )
    }

    @Test
    fun `a blank landing ends the loop rather than retrying blind`() {
        assertEquals(GIVE_UP, decideHistoryStep(null, aCtl, settled = true, canStepFurther = true, attempts = 1, maxAttempts = 5))
        assertEquals(GIVE_UP, decideHistoryStep("  ", aCtl, settled = true, canStepFurther = true, attempts = 1, maxAttempts = 5))
    }

    @Test
    fun `the page's own url wins over the webview's provisional one`() {
        // A click-driven back: wv.url can still name the page we are leaving,
        // while the page itself already reports where it committed.
        assertEquals(a2, historyLandingUrl(a2, b))
        // Nothing probed (page did not answer): the WebView is all there is.
        assertEquals(b, historyLandingUrl(null, b))
        assertEquals(b, historyLandingUrl("about:blank", b))
        assertEquals(b, historyLandingUrl("", b))
        assertNull(historyLandingUrl(null, null))
        assertNull(historyLandingUrl("about:blank", "about:blank"))
    }
}