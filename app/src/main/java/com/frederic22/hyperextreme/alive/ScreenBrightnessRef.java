package com.frederic22.hyperextreme.alive;

/**
 * Remembers the last real screen (lockscreen / home) brightness the framework computed.
 *
 * <p>The doze strategy only sees its own doze brightness, and {@code DisplayPowerRequest.screenBrightness}
 * is the user's setting — not the auto-brightness output the panel will actually land on. The
 * DisplayBrightnessController result is the authoritative value for non-doze policies, so we
 * observe (never modify) it and use it as the AOD ceiling. This keeps the AOD->lockscreen
 * hand-off step-free without ever changing the lockscreen's own brightness.</p>
 */
final class ScreenBrightnessRef {
    private static volatile float value=Float.NaN;

    static float get(){return value;}

    static void observe(Object request,Object state){
        if(request==null||state==null)return;
        try{
            // Only the full-bright policy is the lockscreen/home target. DOZE/DIM/OFF values are
            // lower or zero and must not become the AOD ceiling.
            if(policy(request)!=2)return;
            float brightness=(Float)state.getClass().getMethod("getBrightness").invoke(state);
            if(Float.isFinite(brightness)&&brightness>0f&&brightness<=1f)value=brightness;
        }catch(ReflectiveOperationException|RuntimeException|LinkageError ignored){
            // Keep the previous good reference; never let a probe break brightness updates.
        }
    }

    private static int policy(Object request) throws ReflectiveOperationException{
        for(Class<?> type=request.getClass();type!=null;type=type.getSuperclass()){
            try{
                java.lang.reflect.Field field=type.getDeclaredField("policy");
                field.setAccessible(true);
                return ((Number)field.get(request)).intValue();
            }catch(NoSuchFieldException missing){
                // Walk up to the superclass.
            }
        }
        throw new NoSuchFieldException("policy");
    }
}
