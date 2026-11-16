package com.frederic22.hyperextreme.alive;

import java.util.WeakHashMap;

/**
 * Two small, targeted brightness guards for the lock screen:
 *
 * <ol>
 *   <li><b>AOD → lockscreen hand-off.</b> The panel leaves AOD at the boosted AOD brightness and
 *       the system then ramps down to the lock brightness. A hard step reads as a one-frame
 *       "flash". For a short window after a wake we ease that single downward step at a bounded
 *       rate, exactly like the pre-ui63 build did — but only inside this window, so normal
 *       auto-brightness keeps full responsiveness the rest of the time.</li>
 *   <li><b>invalid_lux dips.</b> Outside the hand-off window, the pipeline occasionally emits an
 *       AutomaticBrightnessStrategy frame with {@code lux=NaN} and a much lower brightness, then
 *       jumps back on the next real sample. Such a frame keeps the previous valid value.</li>
 * </ol>
 *
 * <p>Every valid frame outside those two cases passes through untouched.</p>
 */
final class AodLockBrightness {
    private static final float MIN_DIP = 0.02f;       // ignore sub-2% noise
    private static final float RATE_PER_S = 0.6f;     // max brightness change per second while easing
    private static final long WAKE_WINDOW_MS = 1200L; // only smooth the AOD->lockscreen hand-off
    private static final WeakHashMap<Object,float[]> STATE = new WeakHashMap<>(); // {value, lastMs}
    private static volatile long wakeAt = 0L;

    private AodLockBrightness(){}

    /** Called from the PowerManagerService wake hook. */
    static void onWake(){ wakeAt = android.os.SystemClock.uptimeMillis(); }

    static Object settle(Object controller,Object state){
        if(state==null)return null;
        try {
            float target=(Float)state.getClass().getMethod("getBrightness").invoke(state);
            if(!Float.isFinite(target)||target<=0f||target>1f)return state;
            Object event=state.getClass().getMethod("getBrightnessEvent").invoke(state);
            if(event==null)return state;
            String strategy=(String)event.getClass().getMethod("getDisplayBrightnessStrategyName").invoke(event);
            boolean automatic=strategy!=null&&strategy.contains("AutomaticBrightnessStrategy");
            long now=android.os.SystemClock.uptimeMillis();
            float out=target;
            synchronized(STATE){
                float[] slot=STATE.get(controller);
                if(slot==null){slot=new float[]{Float.NaN,(float)now};STATE.put(controller,slot);}
                float previous=slot[0];
                long last=(long)slot[1];
                slot[1]=(float)now;
                boolean inWake=wakeAt!=0L&&now-wakeAt<WAKE_WINDOW_MS;
                if(automatic&&inWake&&Float.isFinite(previous)&&previous>0f&&target<previous-MIN_DIP){
                    // AOD -> lockscreen: ease the single downward step instead of letting it snap.
                    float dt=Math.min(0.1f,Math.max(0.001f,(now-last)/1000f));
                    float step=RATE_PER_S*dt;
                    float delta=target-previous;
                    if(delta<-step)delta=-step;
                    out=previous+delta;
                }else if(automatic&&!inWake&&Float.isNaN(readLux(event))
                        &&Float.isFinite(previous)&&previous>0f&&target<previous-MIN_DIP){
                    // Steady-state invalid_lux dip: keep the previous valid brightness.
                    out=previous;
                }
                slot[0]=out;
            }
            return out==target?state:MiuiAodPanelBrightness.copyBrightness(state,out);
        }catch(Throwable unavailable){return state;}
    }

    private static float readLux(Object event){
        try{return (Float)event.getClass().getMethod("getLux").invoke(event);}
        catch(Throwable unavailable){return Float.NaN;}
    }
}
