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
 * credential-protected storage. On boot, before the first unlock / before the app process is up, the
 * system cannot bind the HOME wallpaper, so it falls back to the default image wallpaper
 * (wallpaper_info.xml.fallback). The lock wallpaper only binds when you actually lock, by which time
 * the app is ready, so it survives. Nothing re-asserted the HOME wallpaper afterwards.
 *
 * This receiver re-applies our wallpaper components as soon as possible after boot / package update.
 * It polls tightly (attempt immediately, then every ~1.8s for up to 60s) so the default-wallpaper
 * gap is as short as the system allows — the re-apply itself needs root (the hidden
 * setWallpaperComponent API is signature-only), so it can only start once root + WMS are ready.
 * Only runs while Alive is enabled.
 */
public final class AliveBootReceiver extends BroadcastReceiver {
    private static final long WINDOW_MS = 60000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent == null ? "" : String.valueOf(intent.getAction());
        if (!Config.prefs(context).getBoolean("enabled", false)) return;
        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                long deadline = SystemClock.uptimeMillis() + WINDOW_MS;
                int attempt = 0;
                while (SystemClock.uptimeMillis() < deadline) {
                    try {
                        RootBridge.Result result = RootBridge.wallpaper(app, true);
                        Log.i(Protocol.TAG, "Alive boot keep (" + action + ") #" + attempt
                                + ": exit=" + result.code + " " + result.text);
                        if (result.ok() && result.text.contains("PAIR_VERIFIED")) {
                            Config.prefs(app).edit().putBoolean("enabled", true).apply();
                            return;
                        }
                    } catch (Throwable error) {
                        Log.i(Protocol.TAG, "Alive boot keep #" + attempt + " failed: " + error);
                    }
                    attempt++;
                    SystemClock.sleep(attempt <= 1 ? 300L : 1800L);
                }
                Log.i(Protocol.TAG, "Alive boot keep gave up (" + action + ")");
            } finally {
                pending.finish();
            }
        }, "AliveBootKeep").start();
    }
}
