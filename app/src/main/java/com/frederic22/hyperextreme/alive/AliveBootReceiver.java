package com.frederic22.hyperextreme.alive;

import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Schedules the post-boot wallpaper re-assert.
 *
 * <p>On boot HyperOS cannot bind the HOME live wallpaper before the user unlocks, so it falls back
 * to the default image wallpaper. This receiver only decides <em>whether</em> we should re-assert
 * (Alive enabled, and the current wallpaper is still ours or the system fallback) and hands the
 * actual work to {@link AliveBootJobService}, which is allowed to run beyond a broadcast's time
 * limit and retries with backoff while root / WindowManager come up.</p>
 */
public final class AliveBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context,Intent intent){
        if(!shouldKeepWallpaper(context))return;
        AliveBootJobService.schedule(context);
    }

    /** True when Alive is enabled and the user has not deliberately switched to another wallpaper. */
    static boolean shouldKeepWallpaper(Context context){
        try {
            if(!Config.prefs(context).getBoolean("enabled",false))return false;
            WallpaperManager manager=WallpaperManager.getInstance(context);
            WallpaperInfo info=manager==null?null:manager.getWallpaperInfo();
            if(info==null)return true; // default/static fallback: our slot is still the one to restore
            String pkg=info.getComponent().getPackageName();
            return context.getPackageName().equals(pkg)||"com.miui.miwallpaper".equals(pkg);
        }catch(Throwable unavailable){
            Log.w(Protocol.TAG,"Alive boot: cannot inspect wallpaper ("+unavailable+")");
            return false;
        }
    }
}
