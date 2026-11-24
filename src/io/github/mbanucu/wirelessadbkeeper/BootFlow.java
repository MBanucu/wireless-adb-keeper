package io.github.mbanucu.wirelessadbkeeper;

/**
 * The service's boot/unlock state machine, decided without Android.
 *
 * <p>A reboot delivers up to four events to the monitor: {@code
 * LOCKED_BOOT_COMPLETED} starts the service before the first unlock,
 * {@code ACTION_USER_UNLOCKED} follows when credential-protected storage and
 * the Keystore become readable, {@code BOOT_COMPLETED} arrives after the unlock,
 * and the system may start the service again on its own. The saved Wi-Fi mode
 * lives in credential-protected prefs, so only an unlock makes it applicable.
 *
 * <p>The rule this class exists to make explicit: an event before unlock never
 * reports success and never claims the mode was applied. It waits, journals
 * that it is waiting, and the unlock (or a retry) applies the mode. An unlock
 * always applies again, even when an earlier event claimed the mode: applying
 * the mode is idempotent (an unchanged suggestion record is not rewritten) and
 * skipping it is exactly the reboot bug in issue #39.
 *
 * <p>{@link Session} holds the "already applied" bit and calls the {@link
 * Applier}, so a plain JVM test can replay a boot and unlock without Android.
 */
public final class BootFlow {

    /** The events that can ask the monitor to apply the saved Wi-Fi mode. */
    public enum Event {
        SERVICE_CREATED("service created"),
        START_COMMAND("start command"),
        BOOT_COMPLETED("boot completed"),
        USER_UNLOCKED("user unlocked"),
        /** A saved settings change; always re-applies, like an unlock. */
        SETTINGS_CHANGED("settings saved"),
        /** A bounded retry after unlock found storage still locked. */
        RETRY("unlock retry");

        private final String label;

        Event(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** What the monitor should do for one event. */
    public enum Stage {
        /** Credential-protected storage is not readable yet; nothing was applied. */
        WAITING_FOR_UNLOCK,
        /** The applier ran and the mode is now applied. */
        APPLIED,
        /** The mode was applied earlier and this event does not need it again. */
        ALREADY_APPLIED,
        /** The applier ran but the mode could not be applied yet. */
        FAILED
    }

    /**
     * Applies the saved mode. Null means it was applied; otherwise the returned
     * sentence names what still blocks it. It must never throw: the monitor
     * turns a throw into a failed decision, but a plain sentence is the useful
     * journal detail.
     */
    @FunctionalInterface
    public interface Applier {
        String apply(String reason);
    }

    /** One decision, with the journal code and words the monitor writes once. */
    public static final class Decision {
        private final Stage stage;
        private final String code;
        private final String detail;

        Decision(Stage stage, String code, String detail) {
            this.stage = stage;
            this.code = code == null ? "" : code;
            this.detail = detail == null ? "" : detail;
        }

        public Stage stage() {
            return stage;
        }

        /** Empty when there is nothing new to journal (a successful apply journals itself). */
        public String code() {
            return code;
        }

        public String detail() {
            return detail;
        }

        public boolean applies() {
            return stage == Stage.APPLIED || stage == Stage.FAILED;
        }

        public boolean applied() {
            return stage == Stage.APPLIED;
        }

        /** A waiting/failed decision that should be retried after an unlock. */
        public boolean retryable() {
            return stage == Stage.WAITING_FOR_UNLOCK || stage == Stage.FAILED;
        }
    }

    /** The state a service run keeps: only whether the mode is applied. */
    public static final class Session {
        private final Applier applier;
        private boolean applied;
        private Event lastEvent;
        private Stage lastStage = Stage.WAITING_FOR_UNLOCK;

        public Session(Applier applier) {
            this.applier = applier;
        }

        /**
         * Handles one event. Before unlock it never calls the applier; after
         * unlock {@link Event#USER_UNLOCKED} always applies again, while the
         * other events skip an already applied mode so a script's repeated
         * start command cannot re-register the suggestion in a loop.
         */
        public Decision onEvent(Event event, boolean unlocked) {
            lastEvent = event;
            if (!unlocked) {
                lastStage = Stage.WAITING_FOR_UNLOCK;
                return new Decision(Stage.WAITING_FOR_UNLOCK, "svc.mode.pending",
                        event.label() + ": credential storage is locked;"
                                + " the saved Wi-Fi mode is applied after the first unlock");
            }
            if (applied && event != Event.USER_UNLOCKED && event != Event.SETTINGS_CHANGED) {
                lastStage = Stage.ALREADY_APPLIED;
                return new Decision(Stage.ALREADY_APPLIED, "", "");
            }
            String failure;
            try {
                failure = applier.apply(event.label());
            } catch (RuntimeException e) {
                failure = "the saved Wi-Fi mode could not be applied: " + e;
            }
            if (failure != null && !failure.isEmpty()) {
                // An unlock re-applies the mode; if that attempt fails, the
                // earlier "applied" claim is no longer true.
                applied = false;
                lastStage = Stage.FAILED;
                return new Decision(Stage.FAILED, "svc.mode.failed",
                        event.label() + ": " + failure);
            }
            applied = true;
            lastStage = Stage.APPLIED;
            return new Decision(Stage.APPLIED, "", "");
        }

        public boolean applied() {
            return applied;
        }

        /** One line for the diagnostics snapshot: what the mode is doing. */
        public String summary() {
            if (applied) {
                return "applied" + (lastEvent == null ? "" : " (" + lastEvent.label() + ")");
            }
            if (lastStage == Stage.FAILED && lastEvent != null) {
                return "not applied (" + lastEvent.label() + " failed)";
            }
            return "not applied (waiting for unlock)";
        }
    }

    private BootFlow() {
    }

    /**
     * Whether the persisted job should register the suggestions when the
     * monitor service is gone. This is the fallback for a ROM that stops the
     * service after boot: the job still runs, but it must never re-register in
     * local-only mode and cannot read anything before the first unlock.
     */
    public static boolean shouldRegisterFromJob(boolean unlocked, boolean serviceRunning,
                                                boolean autoMode) {
        return unlocked && !serviceRunning && autoMode;
    }
}
