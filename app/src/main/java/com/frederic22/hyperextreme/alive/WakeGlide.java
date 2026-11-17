package com.frederic22.hyperextreme.alive;

import android.os.SystemClock;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

/**
 * Kills the wake brightness flash WITHOUT capping the AOD lift.
 *
 * Measured on this device (ui78 logs): on wake the backlight ramps 316 -> 2325 raw in ~0.5s
 * before settling at the lockscreen level (~316). The first wake brightness event targets a
 * ~6x overshoot and MIUI's gamma RampAnimator recomputes its step from (target - current)
 * every frame, so re-rating mRate (ui78) had no effect.
 *
 * This version clamps the animation itself: during a short window after wake, every
 * brightening step of the main display's backlight RampAnimator is limited to a linear glide
 * from its window-start value to the current target over GLIDE_SECONDS. Whatever bogus target
 * the system proposes, the physical backlight can no longer jump. Darkening steps are left at
 * stock speed (unlock dimming must stay snappy), and the window closes before normal
 * brightness behaviour resumes.
 *
 * All access is reflective and failure-safe: unknown fields/methods or any runtime error make
 * the clamp a silent no-op, i.e. stock behaviour.
 */
final class WakeGlide {
    private static final long WINDOW_MS = 2500L;     // clamp window after wake
    private static final float GLIDE_SECONDS = 1.2f; // RampAnimator rate hint
    private static final long SETTLE_MS = 160L;      // wait for the wake target to stop moving
    private static final long FADE_MS = 200L;        // AOD -> lockscreen cross-fade

    private static volatile long wakeAt = Long.MIN_VALUE;
    private static boolean logged;
    private static final WeakHashMap<Class<?>,Object[]> PARTS = new WeakHashMap<>();
    private static final WeakHashMap<Class<?>,Method> PROPERTY_SET = new WeakHashMap<>();
    private static final WeakHashMap<Object,float[]> BASELINE = new WeakHashMap<>();
    private static final WeakHashMap<Object,SinkState> SINKS = new WeakHashMap<>();
    private static boolean sinkLogged;

    static void onWake(){
        wakeAt=SystemClock.elapsedRealtime();
        logged=false;
        sinkLogged=false;
        synchronized(BASELINE){BASELINE.clear();}
        synchronized(SINKS){for(SinkState s:SINKS.values()){s.base=s.last;s.t0=0L;}}
    }

    /** After RampAnimator.setAnimationTarget*: keep the rate hint (helps plain AOSP ramps). */
    static void glide(Object animator){
        long at=wakeAt;
        if(at==Long.MIN_VALUE)return;
        long delta=SystemClock.elapsedRealtime()-at;
        if(delta<0||delta>WINDOW_MS)return;
        try{
            Object[] p=parts(animator.getClass());
            if(p.length==0)return;
            Field current=(Field)p[0],target=(Field)p[1],rate=(Field)p[2],display=(Field)p[3];
            float cur=current.getFloat(animator),tgt=target.getFloat(animator),old=rate.getFloat(animator);
            if(!(tgt>cur+0.0005f))return;
            if(display.getInt(animator)!=0)return;
            float limited=(tgt-cur)/GLIDE_SECONDS;
            if(!(old>limited))return;
            Method setRate=(Method)p[4];
            if(setRate!=null)setRate.invoke(animator,limited);
            rate.setFloat(animator,limited);
            if(!logged){logged=true;AliveHooks.log("AOD wake glide: brightening clamped to "+GLIDE_SECONDS+"s for "+WINDOW_MS+"ms");}
        }catch(ReflectiveOperationException|RuntimeException|LinkageError ignored){/* stock ramp */}
    }

    /**
     * After RampAnimator.performNextAnimationStep: re-clamp the applied value onto the glide
     * path. This is the effective clamp — it holds regardless of how MIUI computes its steps.
     */
    static void step(Object animator){
        long at=wakeAt;
        if(at==Long.MIN_VALUE)return;
        long delta=SystemClock.elapsedRealtime()-at;
        if(delta<0||delta>WINDOW_MS)return;
        try{
            Object[] p=parts(animator.getClass());
            if(p.length==0)return;
            Field current=(Field)p[0],target=(Field)p[1],display=(Field)p[3];
            if(display.getInt(animator)!=0)return;
            float cur=current.getFloat(animator),tgt=target.getFloat(animator);
            float[] base;
            synchronized(BASELINE){
                base=BASELINE.get(animator);
                if(base==null){base=new float[]{cur};BASELINE.put(animator,base);}
            }
            if(!(tgt>base[0]+0.0005f))return;      // only clamp brightening glides
            float t=Math.min(1f,delta/(GLIDE_SECONDS*1000f));
            float allowed=base[0]+(tgt-base[0])*t;
            if(cur<=allowed+0.0002f)return;        // already on the glide path
            current.setFloat(animator,allowed);
            apply(animator,p,allowed);
        }catch(ReflectiveOperationException|RuntimeException|LinkageError ignored){/* stock step */}
    }

    /** Push the clamped value to the underlying property (the actual backlight write). */
    private static void apply(Object animator,Object[] p,float value){
        try{
            if(p.length<7)return;
            Object property=((Field)p[5]).get(animator);
            Object object=((Field)p[6]).get(animator);
            if(property==null||object==null)return;
            Method set=propertySet(property.getClass());
            if(set!=null)set.invoke(property,object,value);
        }catch(ReflectiveOperationException|RuntimeException ignored){/* value only */}
    }

    /**
     * Final Java exit for panel brightness: SurfaceControl.setDisplayBrightness(token, value).
     * Every brightness write goes through here, whatever RampAnimator/HLG logic produced it.
     *
     * AOD -> lockscreen blending: hold the AOD value through the system's wake transient, wait
     * until the requested value has been stable for SETTLE_MS, then linearly cross-fade to it in
     * FADE_MS. This turns the 0.1s hardware state switch into a ~0.36s blend that the eye reads
     * as "just became the lockscreen", never as a flashbang. Units are caller units (nits);
     * only ratios are used.
     */
    static float sink(Object token,float value){
        if(!Float.isFinite(value)||value<=0f)return value;
        long at=wakeAt;
        if(at==Long.MIN_VALUE)return value;
        long delta=SystemClock.elapsedRealtime()-at;
        SinkState st;
        synchronized(SINKS){
            st=SINKS.get(token);
            if(st==null){st=new SinkState();st.last=value;SINKS.put(token,st);}
        }
        if(delta<0||delta>WINDOW_MS){st.phase=2;st.last=value;return value;}
        long now=SystemClock.elapsedRealtime();
        if(st.phase==2){st.last=value;return value;}
        if(st.phase==0){
            if(st.t0==0L){st.t0=now;st.start=st.last;st.stableRaw=value;st.stableSince=now;}
            else if(Math.abs(value-st.stableRaw)>Math.max(0.5f,Math.abs(st.stableRaw)*0.02f)){
                st.stableRaw=value;st.stableSince=now;
            }
            if(now-st.stableSince>=SETTLE_MS){
                st.phase=1;st.fadeFrom=st.start;st.target=st.stableRaw;st.fadeStart=now;
            }else{
                st.last=st.start; // hold the AOD level while the system transient settles
                if(!sinkLogged){sinkLogged=true;AliveHooks.log("AOD wake glide sink: holding "+st.start+" (target "+value+")");}
                return st.start;
            }
        }
        if(st.phase==1){
            float t=Math.min(1f,(now-st.fadeStart)/(float)FADE_MS);
            float out=st.fadeFrom+(st.target-st.fadeFrom)*t;
            st.last=out;
            if(t>=1f)st.phase=2;
            if(!sinkLogged){sinkLogged=true;AliveHooks.log("AOD wake glide sink: "+st.fadeFrom+" -> "+st.target+" in "+FADE_MS+"ms");}
            return out;
        }
        return value;
    }

    private static final class SinkState{float base,last,start,stableRaw,fadeFrom,target;long t0,stableSince,fadeStart;int phase;}

    private static Object[] parts(Class<?> type){
        Object[] cached;
        synchronized(PARTS){cached=PARTS.get(type);}
        if(cached!=null)return cached;
        Field cur=field(type,"mCurrentValue"),tgt=field(type,"mTargetLinearValue"),
              rate=field(type,"mRate"),id=field(type,"mDisplayId"),
              prop=field(type,"mProperty"),obj=field(type,"mObject");
        Method setRate=method(type,"setRate",float.class);
        Object[] all=cur!=null&&tgt!=null&&rate!=null&&id!=null&&prop!=null&&obj!=null
            ?new Object[]{cur,tgt,rate,id,setRate,prop,obj}
            :new Object[0];
        synchronized(PARTS){PARTS.put(type,all);}
        return all;
    }

    private static Method propertySet(Class<?> type){
        Method cached;
        synchronized(PROPERTY_SET){cached=PROPERTY_SET.get(type);}
        if(cached!=null)return cached;
        Method found=null;
        for(Method m:type.getMethods()){
            if(m.getName().equals("set")&&m.getParameterCount()==2){found=m;break;}
        }
        synchronized(PROPERTY_SET){PROPERTY_SET.put(type,found);}
        return found;
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
