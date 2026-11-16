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
    // (ui60) The AOD lift lives entirely in the doze strategy. This sink layer must stay
    // pass-through: it must never rewrite brightness/sdr/backlight values of other subsystems.
    private final AodPanelHold hold=new AodPanelHold();
    private volatile boolean owned;
    private boolean watching;
    private long bindingRevision;
    private boolean reportedFailure;
    private String lastDecision="";

    synchronized void clear(){lastDecision="";}

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
            // The lock-screen brightness the panel will land on after AOD is the display request's
            // screen brightness (not the doze-only mNormalBrightness, which is much lower). Cap the
            // AOD lift at that value so the hand-off has no step at all.
            float capRef=normal;
            try{
                float screen=(Float)field(request,"screenBrightness");
                if(Float.isFinite(screen)&&screen>0f&&screen<=1f)capRef=screen;
            }catch(ReflectiveOperationException|RuntimeException ignored){/* keep mNormalBrightness */}
            float held;
            synchronized(this){
                String decision="owned="+owned+" full="+full+" interactive="+interactive+" display="+display+" policy="+policy+" state="+state+" level="+level;
                if(!decision.equals(lastDecision)){lastDecision=decision;log.accept("AOD Xiaomi scope "+decision);}
                if(!owned||!eligible||AodBrightness.normalize(level)==0){return original;}
                held=hold.brightness(nativeBrightness,capRef,level,true);
            }
            if(held==nativeBrightness||!AodPanelHold.valid(held))return original;
            Object result=copyBrightness(original,held);
            log.accept("AOD Xiaomi panel native="+nativeBrightness+" entry="+normal+" cap="+capRef+" held="+held+" level="+level);
            return result;
        }catch(ReflectiveOperationException|RuntimeException|LinkageError unavailable){
            clear();
            if(!reportedFailure){reportedFailure=true;log.accept("AOD Xiaomi panel contract unavailable: "+unavailable);}
            return original;
        }
    }

    private static final java.util.WeakHashMap<Class<?>,Object[]> COPY_METHODS=new java.util.WeakHashMap<>();

    static Object copyBrightness(Object original,float brightness) throws ReflectiveOperationException {
        // Clone every native field; downstream thermal/power caps and brightness reasons remain.
        Class<?> state=original.getClass();
        Object[] methods;
        synchronized(COPY_METHODS){
            methods=COPY_METHODS.get(state);
            if(methods==null){
                Class<?> builder=Class.forName(state.getName()+"$Builder",false,state.getClassLoader());
                methods=new Object[]{builder.getMethod("from",state),builder.getMethod("setBrightness",float.class),builder.getMethod("build")};
                COPY_METHODS.put(state,methods);
            }
        }
        Object copy=((java.lang.reflect.Method)methods[0]).invoke(null,original);
        ((java.lang.reflect.Method)methods[1]).invoke(copy,brightness);
        return ((java.lang.reflect.Method)methods[2]).invoke(copy);
    }

    private static final java.util.WeakHashMap<Class<?>,java.util.HashMap<String,Field>> FIELDS=new java.util.WeakHashMap<>();

    private static Object field(Object host,String name) throws ReflectiveOperationException {
        if(host==null)return null;
        Class<?> type=host.getClass();
        synchronized(FIELDS){
            java.util.HashMap<String,Field> map=FIELDS.get(type);
            if(map==null){map=new java.util.HashMap<>();FIELDS.put(type,map);}
            Field cached=map.get(name);
            if(cached!=null)return cached.get(host);
            for(Class<?> scan=type;scan!=null;scan=scan.getSuperclass()){
                try{Field found=scan.getDeclaredField(name);found.setAccessible(true);map.put(name,found);return found.get(host);}
                catch(NoSuchFieldException missing){/* Check the superclass. */}
            }
            throw new NoSuchFieldException(name);
        }
    }
}
