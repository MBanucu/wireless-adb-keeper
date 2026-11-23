package io.github.mbanucu.wirelessadbkeeper;

/**
 * The local-only Wi-Fi retry schedule, without a Handler or a clock.
 *
 * <p>Three failed approvals pause the request; a success resets the attempt
 * budget. Consecutive attempts are at least {@link #MIN_GAP_MS} apart, and a
 * wall/elapsed clock that moves backwards must not turn that gap into a
 * negative delay (a negative value would be handed to a scheduler as an
 * immediate retry, which is the opposite of a backoff).
 *
 * <p>The schedule is 60 s, 5 min, 15 min. The current monitor pauses after the
 * third failure, so the 15-minute entry is the last delay the schedule can
 * describe; it is kept here as the documented ceiling rather than as a fourth
 * retry.
 */
public final class Backoff {

    private static final long RETRY_FIRST_MS = 60_000L;
    private static final long RETRY_SECOND_MS = 5 * 60_000L;
    private static final long RETRY_THIRD_MS = 15 * 60_000L;

    /** Attempts after which the monitor pauses until the user acts. */
    public static final int MAX_ATTEMPTS = 3;
    /** Minimum gap between two network requests. */
    public static final long MIN_GAP_MS = 30_000L;

    private int attempts;
    private long lastAttemptAt;

    public int attempts() {
        return attempts;
    }

    /** True when the automatic retry budget is used up. */
    public boolean exhausted() {
        return attempts >= MAX_ATTEMPTS;
    }

    /** Back to the start of the budget, as on service start or a manual retry. */
    public void reset() {
        attempts = 0;
        lastAttemptAt = 0L;
    }

    /** A successful connection spends no retry and re-arms the whole budget. */
    public void success() {
        attempts = 0;
    }

    /** Drops the minimum-gap anchor without changing the attempt count. */
    public void clearGap() {
        lastAttemptAt = 0L;
    }

    /** Records that a request left now, which starts the minimum-gap window. */
    public void attempted(long now) {
        lastAttemptAt = now;
    }

    /** Records one failed approval. */
    public void failed() {
        attempts++;
    }

    /** The delay after the current failure; 0 when no retry is allowed. */
    public long retryDelayMs() {
        return exhausted() ? 0L : delayForAttempt(attempts);
    }

    /** The schedule entry for {@code attempt} (1-based), clamped at 15 min. */
    public static long delayForAttempt(int attempt) {
        switch (attempt) {
            case 1: return RETRY_FIRST_MS;
            case 2: return RETRY_SECOND_MS;
            default: return attempt <= 0 ? RETRY_FIRST_MS : RETRY_THIRD_MS;
        }
    }

    /**
     * How long to wait before the next request.
     *
     * <p>Zero when nothing has been attempted or the gap has elapsed. A clock
     * that reports an earlier time than the attempt returns the whole gap
     * instead of a delay larger than the gap, and never a negative one.
     */
    public long gapMs(long now) {
        if (lastAttemptAt <= 0L) return 0L;
        if (now < lastAttemptAt) return MIN_GAP_MS;
        long remaining = lastAttemptAt + MIN_GAP_MS - now;
        return Math.max(0L, remaining);
    }
}
