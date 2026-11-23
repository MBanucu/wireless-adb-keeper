package io.github.mbanucu.wirelessadbkeeper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class EnsureReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context context, Intent intent) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Ensurer.ensure(context);
            }
        }).start();
    }
}
