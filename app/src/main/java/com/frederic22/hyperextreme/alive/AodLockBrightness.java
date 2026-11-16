package com.frederic22.hyperextreme.alive;

import java.util.WeakHashMap;

/**
 * Smooths lock-screen brightness across the transient invalid_lux frame the display pipeline
 * emits (a redraw with lux=NaN momentarily reports a lower brightness, then the next frame with a
 * real lux jumps back up). Instead of freezing on the previous value, this slews the brightness
 * toward the system target at a bounded rate, so the change eases in over a few hundred ms and
 * always converges to the real system brightness (never stuck on a held value).
 */
final class AodLockBrightness {
    private static final WeakHashMap<Object,float[]> STATE=new WeakHashMap<>(); // {value,lastMs}
    private static final float RATE_PER_S=0.6f; // max brightness change per second while easing
    private static final float JUMP=0.02f;       // only ease jumps larger than this; small changes pass

    private AodLockBrightness(){}

    static Object settle(Object controller,Object state){
        if(state==null)return null;
        try {
            float target=(Float)state.getClass().getMethod("getBrightness").invoke(state);
            if(!Float.isFinite(target)||target<=0f||target>1f)return state;
            Object event=state.getClass().getMethod("getBrightnessEvent").invoke(state);
            if(event==null)return state;
            String strategy=(String)event.getClass().getMethod("getDisplayBrightnessStrategyName").invoke(event);
            boolean automatic=strategy!=null&&strategy.indexOf("AutomaticBrightnessStrategy")>=0;
            long now=android.os.SystemClock.uptimeMillis();
            float out=target;
            synchronized(STATE){
                float[] s=STATE.get(controller);
                if(s==null){s=new float[]{Float.NaN,(float)now};STATE.put(controller,s);}
                float previous=s[0]; long last=(long)s[1]; s[1]=(float)now;
                if(automatic&&Float.isFinite(previous)&&previous>0f&&Math.abs(target-previous)>JUMP){
                    float dt=Math.min(0.1f,Math.max(0.001f,(now-last)/1000f));
                    float step=RATE_PER_S*dt;
                    float delta=target-previous;
                    if(delta>step)delta=step; else if(delta<-step)delta=-step;
                    out=previous+delta;                    // ease toward the real system brightness
                    android.util.Log.i(Protocol.TAG,"AOD lock ease "+previous+" -> "+out+" (target "+target+")");
                }
                s[0]=out;
            }
            if(out==target)return state;
            return MiuiAodPanelBrightness.copyBrightness(state,out);
        }catch(Throwable unavailable){return state;}
    }
}
