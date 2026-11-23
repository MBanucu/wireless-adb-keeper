package io.github.mbanucu.wirelessadbkeeper.harness;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Runs every suite named on the command line in one JVM.
 *
 * <p>A {@code java} start costs more than a handful of checks, so the suites are
 * started once instead of once per file. The names come from
 * {@code tests/unit.sh}, which discovers the files; nothing is listed here.
 *
 * <p>Each suite is timed into the same {@code timing: <class>#<test> <ms>
 * <passed|failed>} line the sibling project's {@code TestTimings} writes, so a
 * slow suite is on the record instead of only being felt.
 */
public final class SuiteRunner {

    private SuiteRunner() {
    }

    public static void main(String[] args) throws Exception {
        for (String name : args) {
            long started = System.currentTimeMillis();
            run(name);
            System.out.println("timing: " + name + "#main "
                    + (System.currentTimeMillis() - started) + "ms passed");
        }
    }

    /** Calls the suite's {@code main}; its own report decides the exit code. */
    private static void run(String name) throws Exception {
        Method main = Class.forName(name).getMethod("main", String[].class);
        try {
            main.invoke(null, (Object) new String[0]);
        } catch (InvocationTargetException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Error error) {
                throw error;
            }
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw failed;
        }
    }
}
