package com.frederic22.hyperextreme.alive;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.PowerManager;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Adapter for Xiaomi's full-AOD strategy, which bypasses DozeService brightness requests. */
final class MiuiAodPanelBrightness {
    // AOD->lockscreen handoff smoothing: while in AOD the panel sits at the boosted AOD backlight;
    // when the lockscreen appears the framework snaps it down to the lock brightness, which reads as
    // a flashbang. For a short window after a wake we linearly fade the applied panel brightness from
    // the AOD value down to whatever the system now asks for, then hand control back to system auto
    // brightness (pass-through once the fade window ends).
    static volatile float normalCap=Float.NaN; // kept for diagnostics only
    private static volatile float lastSink=Float.NaN;
    private static volatile float fadeFrom=Float.NaN;
    private static volatile long wakeAt=0L;
    private static volatile long fadeStart=0L;
    private static final long WAKE_FADE_MS=450L;
    static void onWake(){long n=android.os.SystemClock.uptimeMillis();wakeAt=n;fadeStart=n;fadeFrom=lastSink;}
    static float clampApplied(float brightness){
        // Only fraction-scale (0,1] brightness. Skip nits/DBV-sized floats (e.g. SurfaceControl ~83.57)
        // that share the same call and must not be rewritten with a 0..1 value.
        if(brightness<=0f||brightness>1f)return brightness;
        long now=android.os.SystemClock.uptimeMillis();
        if(wakeAt==0L||now-wakeAt>WAKE_FADE_MS||!Float.isFinite(fadeFrom)||fadeFrom<=brightness){
            lastSink=brightness;return brightness;
        }
        float t=(float)(now-fadeStart)/(float)WAKE_FADE_MS; if(t<0f)t=0f; else if(t>1f)t=1f;
        float out=fadeFrom+(brightness-fadeFrom)*t;   // linear crossfade AOD -> system target
        if(out<brightness)out=brightness;             // never undershoot the system target
        lastSink=out;
        return out;
    }
    // Clamp every float argument (brightness/sdr/backlight) of an intercepted sink call.
    // Returns a replacement Object[] when at least one value changed, else null.
    static Object[] clampArgs(java.util.List<Object> args,String tag){
        int n=args.size(); boolean changed=false; Object[] out=new Object[n];
        for(int i=0;i<n;i++){
            Object v=args.get(i); out[i]=v;
            if(v instanceof Float){
                float f=(Float)v, c=clampApplied(f);
                if(c!=f){out[i]=Float.valueOf(c);changed=true;
                    android.util.Log.i("HyperExtremeAlive","AOD wake clamp "+tag+"["+i+"]="+f+"->"+c);}
            }
        }
        return changed?out:null;
    }

    private final WeakHashMap<Object,AodPanelHold> sessions=new WeakHashMap<>();
    private volatile boolean owned;
    private boolean watching;
    private long bindingRevision;
    private boolean reportedFailure;
    private String lastDecision="";

    synchronized void clear(){sessions.clear();lastDecision="";}

    // Called only on the config worker; WallpaperManager/ActivityManager IPC must not run
    // in DisplayPowerController's hook/lock. Invalidate before refreshing changed bindings.
    void refresh(Context context,Handler worker){
        if(context==null)return;
        try {
        if(!watching){
            IntentFilter filter=new IntentFilter(Intent.ACTION_WALLPAPER_CHANGED);
            filter.addAction("android.intent.action.USER_SWITCHED");
            context.registerReceiver(new BroadcastReceiver(){
                @Override public void onReceive(Context c,Intent event){
                    synchronized(MiuiAodPanelBrightness.this){owned=false;bindingRevision++;clear();}
                    worker.post(()->refresh(context,worker));
                }
            },filter);
            watching=true;
        }
        long revision;
        synchronized(this){revision=bindingRevision;}
        boolean current=SystemAodDimming.ownsSelectedLock(context);
        synchronized(this){
            if(revision!=bindingRevision)return;
            if(owned!=current)clear();
            owned=current;
        }
        }catch(RuntimeException|LinkageError unavailable){synchronized(this){owned=false;clear();}}
    }

    void powerRequest(Object display,Object request){
        try {
            if(Integer.valueOf(0).equals(field(display,"mDisplayId"))
                    &&(!Integer.valueOf(1).equals(field(request,"policy"))
                        ||Integer.valueOf(1).equals(field(request,"dozeScreenState"))))clear();
        }catch(ReflectiveOperationException|RuntimeException|LinkageError unavailable){clear();}
    }

    static boolean contract(Method method){
        Class<?>[] p=method.getParameterTypes();
        return method.getName().equals("updateBrightness")&&p.length==1
            &&p[0].getName().equals("com.android.server.display.brightness.StrategyExecutionRequest")
            &&method.getReturnType().getName().equals("com.android.server.display.DisplayBrightnessState");
    }

    Object update(Object strategy,Object execution,Object original,float level,Consumer<String> log){
        if(original==null)return null;
        try {
            Object stub=field(strategy,"mDozeBrightnessStrategyStub");
            if(stub==null||!stub.getClass().getName().equals("com.android.server.display.brightness.strategy.DozeBrightnessStrategyImpl"))return original;
            Object request=execution.getClass().getMethod("getDisplayPowerRequest").invoke(execution);
            Context context=(Context)field(stub,"mContext");
            PowerManager power=context==null?null:context.getSystemService(PowerManager.class);
            boolean full=(Boolean)field(stub,"mIsFullAod"),interactive=power==null||power.isInteractive();
            int display=(Integer)field(strategy,"mDisplayId"),policy=(Integer)field(request,"policy"),state=(Integer)field(request,"dozeScreenState");
            boolean eligible=AodPanelHold.eligible(owned,full,interactive,display,policy,state);
            float nativeBrightness=(Float)original.getClass().getMethod("getBrightness").invoke(original);
            Object controller=field(stub,"mDozeAutoBrightnessController");
            float normal=controller==null?Float.NaN:(Float)field(controller,"mNormalBrightness");
            if(Float.isFinite(normal)&&normal>0f)normalCap=normal;
            float held;
            synchronized(this){
                String decision="owned="+owned+" full="+full+" interactive="+interactive+" display="+display+" policy="+policy+" state="+state+" level="+level;
                if(!decision.equals(lastDecision)){lastDecision=decision;log.accept("AOD Xiaomi scope "+decision);}
                if(!owned||!eligible||AodBrightness.normalize(level)==0){sessions.remove(strategy);return original;}
                held=sessions.computeIfAbsent(strategy,key->new AodPanelHold())
                    .brightness(nativeBrightness,normal,level,true);
            }
            if(held==nativeBrightness||!AodPanelHold.valid(held))return original;
            Object result=copyBrightness(original,held);
            log.accept("AOD Xiaomi panel native="+nativeBrightness+" entry="+normal+" held="+held+" level="+level);
            return result;
        }catch(ReflectiveOperationException|RuntimeException|LinkageError unavailable){
            clear();
            if(!reportedFailure){reportedFailure=true;log.accept("AOD Xiaomi panel contract unavailable: "+unavailable);}
            return original;
        }
    }

    static Object copyBrightness(Object original,float brightness) throws ReflectiveOperationException {
        // Clone every native field; downstream thermal/power caps and brightness reasons remain.
        Class<?> state=original.getClass();
        Class<?> builder=Class.forName(state.getName()+"$Builder",false,state.getClassLoader());
        Object copy=builder.getMethod("from",state).invoke(null,original);
        builder.getMethod("setBrightness",float.class).invoke(copy,brightness);
        return builder.getMethod("build").invoke(copy);
    }

    private static Object field(Object host,String name) throws ReflectiveOperationException {
        if(host==null)return null;
        for(Class<?> type=host.getClass();type!=null;type=type.getSuperclass()){
            try{Field field=type.getDeclaredField(name);field.setAccessible(true);return field.get(host);}
            catch(NoSuchFieldException missing){/* Check the superclass. */}
        }
        throw new NoSuchFieldException(name);
    }
}
