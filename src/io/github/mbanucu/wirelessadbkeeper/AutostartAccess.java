package io.github.mbanucu.wirelessadbkeeper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Reads and clicks the DokeOS autostart list on the keeper's behalf.
 *
 * <p>The vendor list has no settings key, provider or broadcast: the only way
 * to change a row is to press its switch, and `uiautomator dump` costs about
 * two seconds per screenful. An accessibility service sees the same nodes
 * in-process, so {@link AutostartSetup} can find the row and click it in
 * milliseconds.
 *
 * <p>The scope comes from {@code res/xml/autostart_service.xml}: the service is
 * declared with {@code packageNames="com.blackview.systemmanager"}, so the
 * framework delivers it that app's windows and no others. It takes no
 * screenshots and performs no gestures.
 *
 * <p>This runs after the first unlock only — accessibility services are not
 * available before it, so it is a setup tool and not a way past the boot gate.
 */
public class AutostartAccess extends AccessibilityService {
    private static final String TAG = Ensurer.TAG;

    @Override
    protected void onServiceConnected() {
        // The metadata declares the vendor package; setting it again here keeps
        // the restriction if the service is ever repointed by a system update
        // that drops the attribute, and makes the intent visible in code.
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.packageNames = new String[]{AutostartSetup.VENDOR_PACKAGE};
            setServiceInfo(info);
        }
        AutostartSetup.attach(this);
        Log.i(TAG, "autostart: accessibility service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Nothing is driven from events: the setup call walks the tree directly
        // and waits for the screen. Subscribing to nothing keeps the power the
        // service holds as small as the job allows.
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        AutostartSetup.detach(this);
        Log.i(TAG, "autostart: accessibility service unbound");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        AutostartSetup.detach(this);
        super.onDestroy();
    }

    /** The active window of the vendor app, or null when another app is in front. */
    AccessibilityNodeInfo root() {
        try {
            return getRootInActiveWindow();
        } catch (Throwable t) {
            Log.w(TAG, "autostart: cannot read the active window", t);
            return null;
        }
    }

    /**
     * Tries to bring the vendor list forward. Android 10+ may refuse a
     * background activity start; that is not fatal, because the setup call also
     * works when adb opened the screen (which is what install.sh does).
     */
    void showVendorScreen() {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.setClassName(AutostartSetup.VENDOR_PACKAGE, AutostartSetup.VENDOR_ACTIVITY);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "autostart: cannot open the vendor screen from here", t);
        }
    }
}
