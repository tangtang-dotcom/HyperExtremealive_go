package com.frederic22.hyperextreme.alive;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

/**
 * Keep the Alive live wallpaper bound across reboots.
 *
 * Why this exists: the Alive wallpaper service runs in the main process and reads its assets from
 * credential-protected storage. On boot, before the first unlock the system cannot bind it and
 * HyperOS falls back to the default image wallpaper (WallpaperManagerService keeps a
 * wallpaper_info.xml.fallback). Nothing re-asserted our wallpaper afterwards, so after every reboot
 * the phone showed the default wallpaper. This receiver re-applies our wallpaper components a few
 * seconds after boot / package update, but only while Alive is enabled.
 */
public final class AliveBootReceiver extends BroadcastReceiver {
    // Retries spread over the first ~30s: root / wallpaper service may not be ready immediately.
    private static final long[] RETRY_MS = {4000L, 9000L, 16000L, 26000L};

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent == null ? "" : String.valueOf(intent.getAction());
        if (!Config.prefs(context).getBoolean("enabled", false)) return;
        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                for (long delay : RETRY_MS) {
                    SystemClock.sleep(delay);
                    try {
                        RootBridge.Result result = RootBridge.wallpaper(app, true);
                        Log.i(Protocol.TAG, "Alive boot keep (" + action + "): exit=" + result.code + " " + result.text);
                        if (result.ok() && result.text.contains("PAIR_VERIFIED")) {
                            Config.prefs(app).edit().putBoolean("enabled", true).apply();
                            return;
                        }
                    } catch (Throwable error) {
                        Log.i(Protocol.TAG, "Alive boot keep attempt failed (" + action + "): " + error);
                    }
                }
                Log.i(Protocol.TAG, "Alive boot keep gave up (" + action + ")");
            } finally {
                pending.finish();
            }
        }, "AliveBootKeep").start();
    }
}
