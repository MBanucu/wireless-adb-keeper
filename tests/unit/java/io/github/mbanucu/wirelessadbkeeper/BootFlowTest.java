package io.github.mbanucu.wirelessadbkeeper;

import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.check;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.checkTrue;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.report;

import java.util.ArrayList;
import java.util.List;

/**
 * The reboot/unlock state machine without Android.
 *
 * <p>The regression this covers is #39: a service start before the first unlock
 * must report that the mode is waiting, not success, and the unlock must apply
 * the saved mode even when an earlier event claimed it. A fake applier records
 * every call, so "re-registered after unlock" is a count, not a guess.
 */
public final class BootFlowTest {

    private BootFlowTest() {
    }

    public static void main(String[] args) {
        beforeUnlockNeverAppliesOrClaimsSuccess();
        aLockedUnlockWaitsAndThenRetries();
        unlockAlwaysAppliesAgain();
        repeatedStartCommandsDoNotReapply();
        settingsChangesReapply();
        aFailedApplyStaysRetryable();
        theJobFallbackOnlyRunsWhenTheServiceIsGone();
        simulatedRebootAndUnlockSequence();
        report("BootFlowTest");
    }

    private static void beforeUnlockNeverAppliesOrClaimsSuccess() {
        FakeApplier applier = new FakeApplier();
        BootFlow.Session session = new BootFlow.Session(applier);

        BootFlow.Decision created =
                session.onEvent(BootFlow.Event.SERVICE_CREATED, false);
        check("a service created before unlock waits",
                BootFlow.Stage.WAITING_FOR_UNLOCK, created.stage());
        check("it never calls the applier before unlock", 0, applier.calls());
        check("it is not reported as applied", false, created.applied());
        check("it journals the pending stage", "svc.mode.pending", created.code());
        checkTrue("the detail says the mode waits for the first unlock",
                created.detail().contains("after the first unlock"));
        check("the session is not applied", false, session.applied());
        check("the snapshot line says not applied", "not applied (waiting for unlock)",
                session.summary());
    }

    private static void aLockedUnlockWaitsAndThenRetries() {
        FakeApplier applier = new FakeApplier();
        BootFlow.Session session = new BootFlow.Session(applier);

        // ACTION_USER_UNLOCKED can arrive while the Keystore/prefs still refuse
        // reads. That is the exact state that used to surface as -2 per profile.
        BootFlow.Decision locked =
                session.onEvent(BootFlow.Event.USER_UNLOCKED, false);
        check("an unlock that still cannot read storage waits",
                BootFlow.Stage.WAITING_FOR_UNLOCK, locked.stage());
        check("it does not register a suggestion with a missing key", 0, applier.calls());
        check("it is retryable", true, locked.retryable());

        applier.failure = null;
        BootFlow.Decision retried = session.onEvent(BootFlow.Event.RETRY, true);
        check("the retry after storage opens applies", BootFlow.Stage.APPLIED,
                retried.stage());
        check("the retry calls the applier once", 1, applier.calls());
        check("the retry names itself", "unlock retry", applier.reasons.get(0));
        check("the session is applied after the retry", true, session.applied());
    }

    private static void unlockAlwaysAppliesAgain() {
        FakeApplier applier = new FakeApplier();
        BootFlow.Session session = new BootFlow.Session(applier);

        session.onEvent(BootFlow.Event.BOOT_COMPLETED, true);
        check("a post-unlock start applies once", 1, applier.calls());

        BootFlow.Decision unlocked =
                session.onEvent(BootFlow.Event.USER_UNLOCKED, true);
        check("the unlock applies even though the mode was applied",
                BootFlow.Stage.APPLIED, unlocked.stage());
        check("the suggestion is registered again after unlock", 2, applier.calls());
        check("both applies name their stage differently", "boot completed",
                applier.reasons.get(0));
        check("the unlock is the second reason", "user unlocked", applier.reasons.get(1));
        check("the snapshot names the unlock", "applied (user unlocked)", session.summary());
    }

    private static void repeatedStartCommandsDoNotReapply() {
        FakeApplier applier = new FakeApplier();
        BootFlow.Session session = new BootFlow.Session(applier);

        session.onEvent(BootFlow.Event.SERVICE_CREATED, true);
        BootFlow.Decision again = session.onEvent(BootFlow.Event.START_COMMAND, true);
        check("a repeated start command is already applied",
                BootFlow.Stage.ALREADY_APPLIED, again.stage());
        check("it does not re-register the suggestion", 1, applier.calls());
        check("it journals nothing", "", again.code());
        check("it is not retryable", false, again.retryable());
    }

    private static void settingsChangesReapply() {
        FakeApplier applier = new FakeApplier();
        BootFlow.Session session = new BootFlow.Session(applier);

        session.onEvent(BootFlow.Event.SERVICE_CREATED, true);
        BootFlow.Decision changed =
                session.onEvent(BootFlow.Event.SETTINGS_CHANGED, true);
        check("a settings change applies after the first pass",
                BootFlow.Stage.APPLIED, changed.stage());
        check("the saved change is the second apply", 2, applier.calls());
    }

    private static void aFailedApplyStaysRetryable() {
        FakeApplier applier = new FakeApplier();
        applier.failure = "credential storage is locked";
        BootFlow.Session session = new BootFlow.Session(applier);

        BootFlow.Decision failed =
                session.onEvent(BootFlow.Event.USER_UNLOCKED, true);
        check("a refused apply is a failed decision", BootFlow.Stage.FAILED, failed.stage());
        check("it is journalled as a failure", "svc.mode.failed", failed.code());
        check("it is not reported as success", false, failed.applied());
        check("it stays retryable", true, failed.retryable());
        check("the session is not applied", false, session.applied());
        check("the snapshot names the failed stage", "not applied (user unlocked failed)",
                session.summary());

        applier.failure = null;
        BootFlow.Decision retried = session.onEvent(BootFlow.Event.RETRY, true);
        check("a later retry applies", BootFlow.Stage.APPLIED, retried.stage());
        check("the session becomes applied", true, session.applied());
    }

    private static void theJobFallbackOnlyRunsWhenTheServiceIsGone() {
        check("after unlock with the service stopped, the job registers", true,
                BootFlow.shouldRegisterFromJob(true, false, true));
        check("a running monitor owns the pass", false,
                BootFlow.shouldRegisterFromJob(true, true, true));
        check("local-only mode has no suggestion to register", false,
                BootFlow.shouldRegisterFromJob(true, false, false));
        check("before unlock the job cannot read the password", false,
                BootFlow.shouldRegisterFromJob(false, false, true));
        check("before unlock is refused even with no service", false,
                BootFlow.shouldRegisterFromJob(false, true, true));
    }

    /** The full replay the real service performs, with the applier as the fake. */
    private static void simulatedRebootAndUnlockSequence() {
        FakeApplier applier = new FakeApplier();
        BootFlow.Session session = new BootFlow.Session(applier);
        List<BootFlow.Decision> decisions = new ArrayList<>();

        decisions.add(session.onEvent(BootFlow.Event.SERVICE_CREATED, false));
        decisions.add(session.onEvent(BootFlow.Event.START_COMMAND, false));
        decisions.add(session.onEvent(BootFlow.Event.BOOT_COMPLETED, false));
        decisions.add(session.onEvent(BootFlow.Event.USER_UNLOCKED, true));
        decisions.add(session.onEvent(BootFlow.Event.START_COMMAND, true));

        check("the sequence waits three times", 3, countStage(
                decisions, BootFlow.Stage.WAITING_FOR_UNLOCK));
        check("the sequence applies once after unlock", 1, countStage(
                decisions, BootFlow.Stage.APPLIED));
        check("the fallback start is already applied", 1, countStage(
                decisions, BootFlow.Stage.ALREADY_APPLIED));
        check("the fake applier ran exactly once", 1, applier.calls());
        check("it ran with the unlock reason", "user unlocked", applier.reasons.get(0));
        check("every pre-unlock decision carries a stage code", true,
                !decisions.get(0).code().isEmpty()
                        && !decisions.get(1).code().isEmpty()
                        && !decisions.get(2).code().isEmpty());
        check("the waiting detail never claims success", false,
                decisions.get(0).applied());
    }

    private static int countStage(List<BootFlow.Decision> decisions, BootFlow.Stage stage) {
        int count = 0;
        for (BootFlow.Decision decision : decisions) {
            if (decision.stage() == stage) count++;
        }
        return count;
    }

    /** Counts and names every apply instead of touching Android. */
    private static final class FakeApplier implements BootFlow.Applier {
        final List<String> reasons = new ArrayList<>();
        String failure;

        int calls() {
            return reasons.size();
        }

        @Override
        public String apply(String reason) {
            reasons.add(reason);
            return failure;
        }
    }
}
