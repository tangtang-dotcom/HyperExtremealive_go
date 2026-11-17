package com.frederic22.hyperextreme.alive;

import android.os.SystemClock;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

/**
 * Solves the AOD -> lockscreen brightness flash WITHOUT capping the AOD lift.
 *
 * The flash is not the AOD value itself: on wake, DisplayPowerState's RampAnimator ramps the
 * backlight from the AOD level toward the lockscreen target with the doze-era rate, which is
 * very fast in brightness terms (341 -> 927 raw in ~1.3s measured), and the first wake
 * BrightnessEvent can even overshoot (0.0465 before settling at 0.0355). The result is a short
 * burst above even the lockscreen level.
 *
 * Instead of limiting the AOD, we limit the ramp: right after wake (PowerManagerService
 * wakeUp*), for a short window every brightening backlight animation on the main display is
 * re-rated so it would need ~GLIDE_SECONDS to close its remaining gap. The backlight then
 * glides smoothly up or stays put without the burst — the AOD keeps its full multiplier range.
 * Darkening animations are untouched (a downward step is normal unlock behaviour and must stay
 * snappy), and the window closes before the user could notice any sluggishness.
 *
 * Signature-independent: works via reflection on whatever setAnimationTarget overload MIUI
 * ships; both setRate(F) and the mRate field are written so gamma-based MIUI ramps pick the
 * new rate up too. Failures are silent no-ops (stock ramp).
 */
final class WakeGlide {
    private static final long WINDOW_MS = 2500L;     // glide window after wake
    private static final float GLIDE_SECONDS = 1.2f; // re-rated brightening ramp duration

    private static volatile long wakeAt = Long.MIN_VALUE;
    private static boolean logged;
    private static final WeakHashMap<Class<?>,Object[]> PARTS = new WeakHashMap<>();

    static void onWake(){wakeAt=SystemClock.elapsedRealtime();logged=false;}

    /** Called after RampAnimator.setAnimationTarget* returns, in system_server. */
    static void glide(Object animator){
        long at=wakeAt;
        if(at==Long.MIN_VALUE)return;
        long delta=SystemClock.elapsedRealtime()-at;
        if(delta<0||delta>WINDOW_MS)return;
        try{
            Object[] parts=parts(animator.getClass());
            if(parts.length==0)return;
            float current=(Float)parts[0].getFloat(animator),target=(Float)parts[1].getFloat(animator),rate=(Float)parts[2].getFloat(animator);
            if(!(target>current+0.0005f))return; // only re-rate brightening ramps
            if(parts[3].getInt(animator)!=0)return;  // main display only
            float limited=(target-current)/GLIDE_SECONDS;
            if(!(rate>limited))return;           // already gentler: keep stock behaviour
            Method setRate=(Method)parts[4];
            if(setRate!=null)setRate.invoke(animator,limited);
            parts[2].setFloat(animator,limited);
            if(!logged){logged=true;AliveHooks.log("AOD wake glide: brightening ramps re-rated to "+GLIDE_SECONDS+"s for "+WINDOW_MS+"ms");}
        }catch(ReflectiveOperationException|RuntimeException|LinkageError ignored){/* stock ramp */}
    }

    private static Object[] parts(Class<?> type){
        Object[] cached=PARTS.get(type);
        if(cached!=null)return cached;
        Field cur=field(type,"mCurrentValue"),tgt=field(type,"mTargetLinearValue"),rate=field(type,"mRate"),id=field(type,"mDisplayId");
        Method setRate=method(type,"setRate",float.class);
        Object[] all=cur!=null&&tgt!=null&&rate!=null&&id!=null?new Object[]{cur,tgt,rate,id,setRate}:new Object[0];
        PARTS.put(type,all);
        return all;
    }

    private static Field field(Class<?> type,String name){
        for(Class<?> c=type;c!=null;c=c.getSuperclass()){
            try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException ignored){}
        }
        return null;
    }

    private static Method method(Class<?> type,String name,Class<?>... params){
        for(Class<?> c=type;c!=null;c=c.getSuperclass()){
            try{Method m=c.getDeclaredMethod(name,params);m.setAccessible(true);return m;}catch(NoSuchMethodException ignored){}
        }
        return null;
    }
}
