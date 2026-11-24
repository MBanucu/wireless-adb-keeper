package io.github.mbanucu.wirelessadbkeeper;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;

/**
 * Switches the keeper's own row on in the DokeOS autostart list.
 *
 * <p>This is the in-process replacement for driving the vendor screen with
 * {@code uiautomator dump} (about two seconds per call, and {@code
 * autostart-allow.sh} needs about ten of them). The nodes are read through
 * {@link AutostartAccess}, which the framework scopes to the vendor package.
 *
 * <p>The two halves it cannot do itself: the service has to be enabled once
 * (`settings put secure enabled_accessibility_services`, done by install.sh or
 * attempted here when the app holds WRITE_SECURE_SETTINGS), and the vendor
 * screen has to be in front (install.sh opens it with `am start`, because a
 * background app may not start an activity).
 *
 * <p>Rows are matched by label and the click goes to the switch, not the text:
 * on this ROM the label is {@code id/autoboot_item_title} and reports {@code
 * clickable=false}, while {@code id/autoboot_item_switch} sits beside it in the
 * same row.
 */
public final class AutostartSetup {
    private static final String TAG = Ensurer.TAG;

    static final String VENDOR_PACKAGE = "com.blackview.systemmanager";
    static final String VENDOR_ACTIVITY = "com.freeme.freemanager.autoboot.AutoStartMainActivity";
    static final String TITLE_ID = VENDOR_PACKAGE + ":id/autoboot_item_title";
    static final String SWITCH_ID = VENDOR_PACKAGE + ":id/autoboot_item_switch";

    /** How long to wait for the vendor screen and for the switch to settle. */
    private static final long WINDOW_TIMEOUT_MS = 6000;
    private static final long SERVICE_TIMEOUT_MS = 4000;
    private static final long SWITCH_TIMEOUT_MS = 2000;
    private static final int MAX_SCROLLS = 12;

    private static volatile AutostartAccess bound;

    private AutostartSetup() {
    }

    static void attach(AutostartAccess service) {
        bound = service;
    }

    static void detach(AutostartAccess service) {
        if (bound == service) bound = null;
    }

    /** The component the secure setting has to name, e.g. {@code pkg/.AutostartAccess}. */
    static String component(Context c) {
        return new ComponentName(c, AutostartAccess.class).flattenToString();
    }

    /** Whether the system currently has this service enabled, as the settings see it. */
    static boolean enabled(Context c) {
        try {
            if (Settings.Secure.getInt(c.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                return false;
            }
            String list = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return list != null && list.contains(component(c));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Adds this service to the enabled list when the app may write secure
     * settings. Returns null on success, or why the write did not take.
     *
     * <p>This is the half of the setup that usually needs adb: the app holds
     * WRITE_SECURE_SETTINGS only because install.sh granted it, so on a stock
     * build this returns the SecurityException and install.sh does the write.
     */
    static String enableSelf(Context c) {
        ContentResolver cr = c.getContentResolver();
        String self = component(c);
        try {
            String list = Settings.Secure.getString(cr,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (list == null || !list.contains(self)) {
                String joined = list == null || list.isEmpty() ? self : list + ":" + self;
                Settings.Secure.putString(cr,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, joined);
            }
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (Throwable t) {
            return t.toString();
        }
        // The write can be accepted and then reverted by the accessibility
        // manager, so the answer comes from reading it back, not from the call.
        if (!enabled(c)) return "the system kept the previous accessibility setting";
        return null;
    }

    /** The label the vendor list shows for this app. */
    static String selfLabel(Context c) {
        try {
            PackageManager pm = c.getPackageManager();
            CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(c.getPackageName(), 0));
            if (label != null && label.length() > 0) return label.toString();
        } catch (Throwable ignored) {
            // The manifest label is what the list shows; a lookup failure is not
            // a reason to skip the setup, only to use the default.
        }
        return "Wireless ADB Keeper";
    }

    /**
     * Ensures the row is on. Returns the control reply: {@code result=allowed}
     * or {@code result=blocked} with what was seen, or {@code error=...}.
     */
    static String ensureOn(Context c, String label) {
        String target = label == null || label.isEmpty() ? selfLabel(c) : label;

        if (!enabled(c)) {
            String problem = enableSelf(c);
            if (problem != null) {
                return "error=the accessibility service is off and cannot be enabled here"
                        + " (" + problem + "); run install.sh, or enable "
                        + component(c) + " in Settings > Accessibility";
            }
            Log.i(TAG, "autostart: enabled the accessibility service from the app");
        }

        AutostartAccess service = awaitService(SERVICE_TIMEOUT_MS);
        if (service == null) {
            return "error=the accessibility service is enabled but not connected;"
                    + " reopen the keeper and retry";
        }

        // Only open the screen when the list is not already there. A vendor
        // window can be focused before its list has been laid out, so the test
        // is for the rows themselves, not for any window of the package.
        if (rowsNow(service) == null) {
            service.showVendorScreen();
            if (rowsOf(service) == null) {
                return "error=the autostart screen did not open; start "
                        + VENDOR_PACKAGE + "/" + VENDOR_ACTIVITY + " and retry";
            }
        }

        AccessibilityNodeInfo title = scrollTo(service, target);
        if (title == null) {
            return "error=" + target + " is not on the autostart screen; open "
                    + VENDOR_PACKAGE + "/" + VENDOR_ACTIVITY + " and retry";
        }
        AccessibilityNodeInfo toggle = switchFor(title);
        if (toggle == null) {
            return "error=the " + target + " row has no switch";
        }

        boolean before = toggle.isChecked();
        if (!before) {
            if (!toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return "error=the switch refused the click";
            }
        }
        boolean after = awaitState(service, target, true, SWITCH_TIMEOUT_MS);
        return "result=" + (after ? "allowed" : "blocked")
                + "\nlabel=" + target
                + "\nbefore=" + (before ? "on" : "off")
                + "\nafter=" + (after ? "on" : "off");
    }

    private static AutostartAccess awaitService(long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        AutostartAccess service = bound;
        while (service == null && SystemClock.uptimeMillis() < deadline) {
            sleep(100);
            service = bound;
        }
        return service;
    }

    /** The list's root if it is on screen right now, without waiting. */
    private static AccessibilityNodeInfo rowsNow(AutostartAccess service) {
        AccessibilityNodeInfo root = service.root();
        return root != null && hasRows(root) ? root : null;
    }

    /** The list's root once its rows exist, or null while it is not on screen. */
    private static AccessibilityNodeInfo rowsOf(AutostartAccess service) {
        long deadline = SystemClock.uptimeMillis() + WINDOW_TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            AccessibilityNodeInfo root = service.root();
            if (root != null && hasRows(root)) return root;
            sleep(100);
        }
        return null;
    }

    private static boolean hasRows(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int guard = 0;
        while (!queue.isEmpty() && guard++ < 4000) {
            AccessibilityNodeInfo node = queue.removeFirst();
            if (TITLE_ID.equals(node.getViewIdResourceName())) return true;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
        return false;
    }

    /**
     * Finds the label, scrolling the list forward when it is below the fold.
     * The list opens at the top, and the label is the app's own name, so this
     * walks down until the row is visible instead of guessing a row number.
     */
    private static AccessibilityNodeInfo scrollTo(AutostartAccess service, String label) {
        long deadline = SystemClock.uptimeMillis() + WINDOW_TIMEOUT_MS;
        for (int attempt = 0; attempt <= MAX_SCROLLS; attempt++) {
            AccessibilityNodeInfo root = service.root();
            if (root != null) {
                AccessibilityNodeInfo title = findText(root, label);
                if (title != null) return title;
                if (attempt == MAX_SCROLLS || !scrollForward(root)) break;
            } else if (SystemClock.uptimeMillis() >= deadline) {
                break;
            }
            // One screenful per action; 200 ms is about twelve frames, enough
            // for the list to lay out before the next read.
            sleep(200);
        }
        return null;
    }

    /** Reads the switch until it reports {@code want}, or the timeout passes. */
    private static boolean awaitState(AutostartAccess service, String label, boolean want,
                                      long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            AccessibilityNodeInfo root = service.root();
            AccessibilityNodeInfo title = root == null ? null : findText(root, label);
            AccessibilityNodeInfo toggle = title == null ? null : switchFor(title);
            if (toggle != null && toggle.isChecked() == want) return true;
            sleep(150);
        }
        return false;
    }

    private static AccessibilityNodeInfo findText(AccessibilityNodeInfo root, String text) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int guard = 0;
        while (!queue.isEmpty() && guard++ < 4000) {
            AccessibilityNodeInfo node = queue.removeFirst();
            CharSequence value = node.getText();
            if (value != null && text.contentEquals(value)) return node;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
        return null;
    }

    /**
     * The switch belonging to the label's row: the label itself is not
     * clickable, so walk up to the row and take the checkable node inside it.
     */
    private static AccessibilityNodeInfo switchFor(AccessibilityNodeInfo title) {
        AccessibilityNodeInfo row = title;
        for (int up = 0; up < 4 && row != null; up++) {
            AccessibilityNodeInfo toggle = findSwitch(row);
            if (toggle != null) return toggle;
            row = row.getParent();
        }
        return null;
    }

    private static AccessibilityNodeInfo findSwitch(AccessibilityNodeInfo row) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(row);
        int guard = 0;
        while (!queue.isEmpty() && guard++ < 200) {
            AccessibilityNodeInfo node = queue.removeFirst();
            if (SWITCH_ID.equals(node.getViewIdResourceName())) return node;
            if (node.isCheckable() && node.isClickable()) return node;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
        return null;
    }

    private static boolean scrollForward(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int guard = 0;
        while (!queue.isEmpty() && guard++ < 4000) {
            AccessibilityNodeInfo node = queue.removeFirst();
            if (node.isScrollable()) {
                return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
        return false;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
