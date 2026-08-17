package com.embl.ebi.funding.client;

import com.embl.ebi.funding.exception.EuropePmcUpstreamException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * A single, deliberately minimal retry helper for transient transport-level failures against
 * Europe PMC (connection resets, timeouts, 5xx) — not a general-purpose resilience framework.
 *
 * <p>Resilience4j was considered and deliberately not used: neither Europe PMC API publishes a
 * documented SLA or rate limit to tune a circuit breaker/bulkhead against, so any threshold values
 * would be invented rather than evidence-based. A small bounded retry plus honest per-item failure
 * reporting (see {@code GrantResolutionService} and the pagination handling in
 * {@code EuropePmcArticleClientImpl}) covers the realistic failure modes — transient blips and
 * sustained outages — without pulling in a framework whose configuration knobs I have no data to
 * fill.
 *
 * <p>Only retries {@link EuropePmcUpstreamException} (transport/parse failures). Query-rejection
 * errors ({@code EuropePmcQueryException}) are never retried, since retrying a malformed query
 * cannot succeed.
 */
public final class RetrySupport {

    private RetrySupport() {
    }

    public static <T> T withRetry(int attempts, long delayMs, Supplier<T> action) {
        return withRetry(attempts, delayMs, action, null);
    }

    /**
     * @param onRetry called with {@code (attemptNumber, exception)} when a failed attempt will be
     *                retried — intended for logging; ignored if {@code null}
     */
    public static <T> T withRetry(int attempts, long delayMs, Supplier<T> action,
                                   BiConsumer<Integer, EuropePmcUpstreamException> onRetry) {
        int totalAttempts = Math.max(1, attempts);
        EuropePmcUpstreamException lastFailure = null;
        for (int attempt = 1; attempt <= totalAttempts; attempt++) {
            try {
                return action.get();
            } catch (EuropePmcUpstreamException e) {
                lastFailure = e;
                if (attempt < totalAttempts) {
                    if (onRetry != null) {
                        onRetry.accept(attempt, e);
                    }
                    sleep(delayMs);
                }
            }
        }
        throw lastFailure;
    }

    private static void sleep(long delayMs) {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
