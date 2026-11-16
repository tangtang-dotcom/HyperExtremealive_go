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
            if(info==null){
                // A null WallpaperInfo also covers a user-set static image. Only the genuine
                // "no wallpaper at all" fallback should be re-asserted; if a static file exists
                // the user has made a choice and we must leave it alone.
                boolean staticFile=false;
                try{
                    android.os.ParcelFileDescriptor file=manager==null?null:manager.getWallpaperFile(WallpaperManager.FLAG_SYSTEM);
                    staticFile=file!=null;
                    if(file!=null)file.close();
                }catch(Throwable ignored){/* query not permitted: treat as no static file */ }
                return !staticFile;
            }
            String pkg=info.getComponent().getPackageName();
            return context.getPackageName().equals(pkg)||"com.miui.miwallpaper".equals(pkg);
        }catch(Throwable unavailable){
            Log.w(Protocol.TAG,"Alive boot: cannot inspect wallpaper ("+unavailable+")");
            return false;
        }
    }
}
