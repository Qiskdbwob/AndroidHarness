package com.androidharness.app.browser

/**
 * What to do after one step through WebView history.
 *
 * The step is a single `goBack()`/`goForward()`, and the question is whether
 * the page that resulted is the one the caller asked for. Getting this wrong in
 * either direction is user-visible: retrying too eagerly walks several entries
 * back, and not retrying at all leaves a 301 bounce on the wrong page.
 *
 * On-device QA (2026-09-17) hit the eager case: navigate A → click a link → back
 * landed on an entry OLDER than the page just left. The step had retried because
 * (a) its landing check ignored whether the new document had actually committed,
 * and (b) [sameDocument] ignored the query string, so every `Apage.html?...`
 * looked like the same page. The loop then kept stepping until history ran out.
 */
internal enum class HistoryStepDecision {
    /** Landed on a different document: done. */
    DONE,

    /** A navigation completed and left us where we started (a redirect bounce): step again. */
    RETRY,

    /**
     * Nothing can be concluded: the landing was not observed to finish loading,
     * the step ran out of attempts, or history had nowhere left to go. Step
     * again would risk skipping an entry, so the state is reported as-is.
     */
    GIVE_UP,
}

/**
 * Decides the next move from what was actually observed. [settled] is the
 * crucial input: a URL read while a navigation is still in flight is the
 * PREVIOUS document's, which is exactly how a landing used to look unchanged.
 */
internal fun decideHistoryStep(
    landedUrl: String?,
    startUrl: String,
    settled: Boolean,
    canStepFurther: Boolean,
    attempts: Int,
    maxAttempts: Int,
): HistoryStepDecision = when {
    landedUrl.isNullOrBlank() -> HistoryStepDecision.GIVE_UP
    !sameDocument(landedUrl, startUrl) -> HistoryStepDecision.DONE
    // Still on the starting page. Only a COMPLETED navigation that came back
    // here is a redirect bounce worth another step; an unfinished one proves
    // nothing, and stepping again would eat the entry we wanted.
    !settled -> HistoryStepDecision.GIVE_UP
    attempts >= maxAttempts -> HistoryStepDecision.GIVE_UP
    !canStepFurther -> HistoryStepDecision.GIVE_UP
    else -> HistoryStepDecision.RETRY
}

/**
 * True when two URLs address the same document. The query string and fragment
 * are part of a document's identity: `/Apage.html?fix=A2` and
 * `/Apage.html?fix=ctl1` are different history entries, and treating them as
 * one made every step look like it had failed to move. Only a trailing slash
 * and an empty query are ignored.
 */
internal fun sameDocument(urlA: String, urlB: String): Boolean {
    fun normalize(url: String): String {
        val trimmed = url.trim().removeSuffix("/")
        return if (trimmed.endsWith("?")) trimmed.dropLast(1) else trimmed
    }
    return normalize(urlA).equals(normalize(urlB), ignoreCase = true)
}

/**
 * The URL of the document a history step should be considered to have landed
 * on. Prefers the page's own `location.href` (which moves at commit) and falls
 * back to the WebView's URL only when the page could not be asked, where a
 * provisional value has to be reported rather than nothing.
 *
 * `about:blank` is useless either way: it is what an uninitialized or torn-down
 * WebView reports, and treating it as a landing would read as "moved" for a
 * step that never happened.
 */
internal fun historyLandingUrl(probedUrl: String?, webViewUrl: String?): String? {
    fun usable(url: String?): String? = url?.trim()?.takeIf { it.isNotEmpty() && it != "about:blank" }
    return usable(probedUrl) ?: usable(webViewUrl)
}