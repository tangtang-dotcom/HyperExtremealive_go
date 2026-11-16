package com.frederic22.hyperextreme.alive;

import java.util.WeakHashMap;

/**
 * Holds the last valid auto-brightness across the transient invalid_lux frame that the
 * display pipeline emits on the lock screen (and every few updates). Without this, one
 * update reports lux=NaN -> a lower brightness, and the next update with a real lux jumps
 * back up, producing the visible flicker. We reuse the previous valid value for that single
 * invalid frame so the panel stays put instead of dipping and rebounding.
 */
final class AodLockBrightness {
    private static final WeakHashMap<Object,float[]> LAST=new WeakHashMap<>();

    private AodLockBrightness(){}

    static Object settle(Object controller,Object state){
        if(state==null)return null;
        try {
            float brightness=(Float)state.getClass().getMethod("getBrightness").invoke(state);
            Object event=state.getClass().getMethod("getBrightnessEvent").invoke(state);
            if(event==null||!Float.isFinite(brightness))return state;
            String strategy=(String)event.getClass().getMethod("getDisplayBrightnessStrategyName").invoke(event);
            float lux=(Float)event.getClass().getMethod("getLux").invoke(event);
            boolean automatic=strategy!=null&&strategy.indexOf("AutomaticBrightnessStrategy")>=0;
            float[] slot;
            synchronized(LAST){slot=LAST.get(controller);if(slot==null){slot=new float[]{Float.NaN};LAST.put(controller,slot);}}
            if(!automatic||!Float.isNaN(lux)){slot[0]=brightness;return state;}
            float previous=slot[0];
            if(Float.isFinite(previous)&&previous>0f&&brightness>0f&&brightness<previous*0.90f){
                Object held=MiuiAodPanelBrightness.copyBrightness(state,previous);
                android.util.Log.i("HyperExtremeAlive","AOD lock debounce invalid_lux "+brightness+"->"+previous);
                return held;
            }
            return state;
        }catch(Throwable unavailable){return state;}
    }
}
