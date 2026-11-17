package com.frederic22.hyperextreme.alive;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Fast downward AOD ambient follow. DozeAutoBrightnessController smooths lux through a ring
 * buffer and only commits mScreenAutoBrightness after brightening/darkening threshold
 * hysteresis, so after walking back into shade the AOD panel stays bright for a long while.
 * This collapses ONLY the downward move: whenever updateAutoBrightness() produced a lower
 * mCurrentBrightness than the committed mScreenAutoBrightness, the commit is applied at once.
 * Brightening keeps the stock anti-flicker debounce, so bright transitions never strobe.
 * Everything degrades silently to stock behavior if the field names ever move.
 */
final class DozeFastDown {
    private static final Map<Class<?>,HashMap<String,Field>> FIELDS = new WeakHashMap<>();
    private static volatile long lastLogAt;

    private DozeFastDown(){}

    static void apply(Object controller){
        if(controller==null)return;
        try{
            float current=field(controller,"mCurrentBrightness").getFloat(controller);
            float screen=field(controller,"mScreenAutoBrightness").getFloat(controller);
            if(Float.isFinite(current)&&Float.isFinite(screen)&&current<screen){
                field(controller,"mScreenAutoBrightness").setFloat(controller,current);
                long now=System.currentTimeMillis();
                if(now-lastLogAt>2000L){
                    lastLogAt=now;
                    AliveHooks.log("AOD fast-down: "+screen+" -> "+current);
                }
            }
        }catch(Throwable unavailable){/* Stock hysteresis still applies; nothing to recover. */}
    }

    private static Field field(Object host,String name) throws NoSuchFieldException {
        Class<?> type=host.getClass();
        synchronized(FIELDS){
            HashMap<String,Field> map=FIELDS.get(type);
            if(map==null){map=new HashMap<>();FIELDS.put(type,map);}
            Field cached=map.get(name);
            if(cached!=null)return cached;
            for(Class<?> scan=type;scan!=null;scan=scan.getSuperclass()){
                try{
                    Field found=scan.getDeclaredField(name);
                    found.setAccessible(true);
                    map.put(name,found);
                    return found;
                }catch(NoSuchFieldException missing){/* Continue up the hierarchy. */}
            }
            throw new NoSuchFieldException(name);
        }
    }
}
