package io.github.mbanucu.wirelessadbkeeper.harness;

import java.util.Objects;

/**
 * Shared bookkeeping for the JVM suites: one {@code ok}/{@code FAIL} line per
 * check and a count per suite.
 *
 * <p>The suites are final classes with a {@code main}, not framework tests:
 * JUnit does not resolve from the offline Gradle cache this project builds from
 * on the phone. {@link SuiteRunner} calls each {@code main} in one JVM, so the
 * counters are reset by {@link #report(String)}; otherwise every suite would
 * take the checks before it as its own.
 */
public final class TestSupport {

    private static int passed;
    private static int failed;

    private TestSupport() {
    }

    /** Compares two values; {@code null} on either side is a value, not a crash. */
    public static void check(String what, Object expected, Object actual) {
        if (Objects.equals(expected, actual)) {
            passed++;
            System.out.println("ok   " + what);
        } else {
            failed++;
            System.out.println("FAIL " + what
                    + ": expected <" + expected + "> got <" + actual + ">");
        }
    }

    public static void checkTrue(String what, boolean actual) {
        check(what, true, actual);
    }

    /**
     * Runs {@code body} and checks that it threw the expected type.
     *
     * <p>Accepts a {@link Body} rather than a {@link Runnable} so a suite can
     * call code that declares checked exceptions without a wrapping try/catch.
     */
    public static void checkThrows(String what, Class<? extends Throwable> expected, Body body) {
        try {
            body.run();
        } catch (Throwable thrown) {
            if (expected.isInstance(thrown)) {
                passed++;
                System.out.println("ok   " + what);
            } else {
                failed++;
                System.out.println("FAIL " + what + ": expected <" + expected.getSimpleName()
                        + "> got <" + thrown.getClass().getSimpleName() + ">");
            }
            return;
        }
        failed++;
        System.out.println("FAIL " + what + ": expected <" + expected.getSimpleName()
                + "> but nothing was thrown");
    }

    /**
     * Prints the suite's result and fails the process when a check did.
     *
     * <p>The counters start again afterwards: SuiteRunner runs every suite in one
     * JVM and they share this class.
     */
    public static void report(String suite) {
        boolean failedAny = failed != 0;
        System.out.println(failedAny
                ? "FAIL " + failed + " of " + (passed + failed) + " " + suite
                : "PASS " + passed + " " + suite);
        passed = 0;
        failed = 0;
        if (failedAny) {
            System.exit(1);
        }
    }

    /** A check body that may throw anything, including a checked exception. */
    @FunctionalInterface
    public interface Body {
        void run() throws Throwable;
    }
}
