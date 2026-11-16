package com.frederic22.hyperextreme.alive;

import java.util.WeakHashMap;

/**
 * Suppresses the one-frame lockscreen brightness dip caused by an invalid ambient-lux sample.
 *
 * <p>The display pipeline occasionally emits an AutomaticBrightnessStrategy frame with lux=NaN
 * (the {@code invalid_lux} flag). That frame carries a much lower brightness; a few milliseconds
 * later the next frame with a real lux jumps back up. This class remembers the last <em>valid</em>
 * automatic brightness and substitutes it only for such invalid, lower frames. Every valid frame
 * passes through untouched, so normal auto-brightness keeps its full responsiveness.</p>
 */
final class AodLockBrightness {
    private static final float MIN_DIP = 0.02f; // ignore sub-2% noise
    private static final WeakHashMap<Object,Float> LAST_VALID = new WeakHashMap<>();

    private AodLockBrightness(){}

    static Object settle(Object controller,Object state){
        if(state==null)return null;
        try {
            Object event=state.getClass().getMethod("getBrightnessEvent").invoke(state);
            if(event==null)return state;
            String strategy=(String)event.getClass().getMethod("getDisplayBrightnessStrategyName").invoke(event);
            if(strategy==null||!strategy.contains("AutomaticBrightnessStrategy"))return state;
            float lux=(Float)event.getClass().getMethod("getLux").invoke(event);
            float target=(Float)state.getClass().getMethod("getBrightness").invoke(state);
            if(!Float.isFinite(target)||target<=0f||target>1f)return state;
            synchronized(LAST_VALID){
                if(!Float.isNaN(lux)){                 // valid frame: remember it and pass through
                    LAST_VALID.put(controller,target);
                    return state;
                }
                Float previous=LAST_VALID.get(controller);
                if(previous==null||previous<=0f||target>=previous-MIN_DIP)return state;
                return MiuiAodPanelBrightness.copyBrightness(state,previous);
            }
        }catch(Throwable unavailable){return state;}
    }
}
