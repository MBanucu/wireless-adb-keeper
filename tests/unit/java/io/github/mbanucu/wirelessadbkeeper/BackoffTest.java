package io.github.mbanucu.wirelessadbkeeper;

import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.check;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.checkTrue;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.report;

/** The 60 s / 5 min / 15 min retry schedule, the gap and the attempts budget. */
public final class BackoffTest {

    private BackoffTest() {
    }

    public static void main(String[] args) {
        check("the first retry waits 60 seconds", 60_000L, Backoff.delayForAttempt(1));
        check("the second retry waits 5 minutes", 300_000L, Backoff.delayForAttempt(2));
        check("the third retry waits 15 minutes", 900_000L, Backoff.delayForAttempt(3));
        check("the schedule does not grow past 15 minutes", 900_000L,
                Backoff.delayForAttempt(4));
        checkTrue("the retry delays are monotonically increasing",
                Backoff.delayForAttempt(1) < Backoff.delayForAttempt(2)
                        && Backoff.delayForAttempt(2) < Backoff.delayForAttempt(3));

        Backoff backoff = new Backoff();
        check("a fresh budget has no attempts", 0, backoff.attempts());
        checkTrue("a fresh budget is not exhausted", !backoff.exhausted());
        backoff.failed();
        check("one failure is one attempt", 1, backoff.attempts());
        check("one failure schedules the 60-second retry", 60_000L, backoff.retryDelayMs());
        backoff.failed();
        check("two failures schedule the 5-minute retry", 300_000L, backoff.retryDelayMs());
        backoff.failed();
        check("three failures exhaust the automatic budget", 3, backoff.attempts());
        checkTrue("the budget is exhausted after three failures", backoff.exhausted());
        check("an exhausted budget schedules no further retry", 0L, backoff.retryDelayMs());

        backoff.success();
        check("a success clears the attempt count", 0, backoff.attempts());
        checkTrue("a success re-arms the budget", !backoff.exhausted());
        check("a success schedules the first retry again", 60_000L, backoff.retryDelayMs());

        Backoff gap = new Backoff();
        gap.attempted(1_000_000L);
        check("the minimum gap is 30 seconds", 30_000L, Backoff.MIN_GAP_MS);
        check("a request just made waits the whole minimum gap", 30_000L,
                gap.gapMs(1_000_000L));
        check("halfway through the gap waits the remainder", 15_000L,
                gap.gapMs(1_015_000L));
        check("a gap that has elapsed waits nothing", 0L, gap.gapMs(1_030_000L));
        check("an old attempt does not produce a wait", 0L, gap.gapMs(2_000_000L));

        check("a clock that jumps backwards never yields a negative delay", 30_000L,
                gap.gapMs(999_000L));
        checkTrue("even a far-backwards clock is non-negative",
                gap.gapMs(Long.MIN_VALUE) >= 0L);
        checkTrue("a backwards clock waits at most the minimum gap",
                gap.gapMs(0L) <= Backoff.MIN_GAP_MS);

        gap.clearGap();
        check("clearing the gap forgets the last request", 0L, gap.gapMs(1_000_000L));
        gap.failed();
        gap.failed();
        gap.reset();
        check("reset clears the attempt count", 0, gap.attempts());
        check("reset clears the gap anchor", 0L, gap.gapMs(1_000_000L));

        report("BackoffTest");
    }
}
