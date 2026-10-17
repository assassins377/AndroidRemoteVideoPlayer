package com.assassins377.remoteplayer.data

import kotlinx.coroutines.delay

/** Exponential backoff policy. Attempt n (0-based) waits initialDelay * factor^n, capped by maxDelay. */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelayMs: Long = 300,
    val factor: Double = 2.0,
    val maxDelayMs: Long = 3_000,
) {
    fun delayFor(attempt: Int): Long {
        var d = initialDelayMs.toDouble()
        repeat(attempt) { d *= factor }
        return d.toLong().coerceAtMost(maxDelayMs)
    }
}

/** Blocking retry (for Media3 DataSource, which runs on a loader thread). */
fun <T> retryBlocking(
    policy: RetryPolicy = RetryPolicy(),
    isRetryable: (Throwable) -> Boolean = { SmbErrors.classify(it).retryable },
    onRetry: (attempt: Int, error: Throwable) -> Unit = { _, _ -> },
    sleep: (Long) -> Unit = { Thread.sleep(it) },
    block: (attempt: Int) -> T,
): T {
    var attempt = 0
    while (true) {
        try {
            return block(attempt)
        } catch (e: Throwable) {
            if (e is InterruptedException || attempt + 1 >= policy.maxAttempts || !isRetryable(e)) throw e
            onRetry(attempt, e)
            sleep(policy.delayFor(attempt))
            attempt++
        }
    }
}

/** Suspending retry for repository/UI calls. */
suspend fun <T> retrySuspend(
    policy: RetryPolicy = RetryPolicy(),
    isRetryable: (Throwable) -> Boolean = { SmbErrors.classify(it).retryable },
    onRetry: (attempt: Int, error: Throwable) -> Unit = { _, _ -> },
    block: suspend (attempt: Int) -> T,
): T {
    var attempt = 0
    while (true) {
        try {
            return block(attempt)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException || attempt + 1 >= policy.maxAttempts || !isRetryable(e)) throw e
            onRetry(attempt, e)
            delay(policy.delayFor(attempt))
            attempt++
        }
    }
}
