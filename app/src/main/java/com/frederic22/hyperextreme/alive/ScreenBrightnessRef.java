package com.frederic22.hyperextreme.alive;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers the last real screen (lockscreen / home) brightness the framework computed.
 *
 * <p>The doze strategy only sees its own doze brightness, and the display request's
 * {@code screenBrightness} is just the user's setting — neither is the value the panel will
 * actually land on after AOD. The DisplayBrightnessController result for the bright policy is
 * the authoritative lockscreen target, so we observe (never modify) it and use it as the AOD
 * ceiling. This keeps the AOD->lockscreen hand-off step-free without touching the lockscreen's
 * own brightness.</p>
 */
final class ScreenBrightnessRef {
    private static final int POLICY_BRIGHT=2; // DisplayPowerRequest.POLICY_BRIGHT
    private static volatile float value=Float.NaN;
    private static volatile Method reader;    // DisplayBrightnessState#getBrightness()
    private static volatile Field policy;     // DisplayPowerRequest#policy
    private static final Set<String> NOT_A_REQUEST=ConcurrentHashMap.newKeySet();
    private static final Set<Integer> SEEN_POLICIES=ConcurrentHashMap.newKeySet();
    private static boolean warned;

    static float get(){return value;}

    /** Signature-agnostic: scans the call's arguments for the DisplayPowerRequest. */
    static void observe(List<Object> args,Object state){
        if(state==null)return;
        try{
            Object request=findRequest(args);
            if(request==null)return;
            int policyValue=((Number)policy.get(request)).intValue();
            if(SEEN_POLICIES.add(policyValue))
                android.util.Log.i(Protocol.TAG,"Alive screen-brightness ref saw policy="+policyValue);
            if(policyValue!=POLICY_BRIGHT)return; // DOZE/DIM/OFF are not lockscreen targets
            Method read=reader;
            if(read==null||!read.getDeclaringClass().isAssignableFrom(state.getClass())){
                read=state.getClass().getMethod("getBrightness");reader=read;
            }
            float brightness=(Float)read.invoke(state);
            if(AodPanelHold.valid(brightness))value=brightness;
        }catch(ReflectiveOperationException|RuntimeException|LinkageError error){
            // Never let a read-only probe break the brightness pipeline; log once for diagnosis.
            if(!warned){warned=true;android.util.Log.w(Protocol.TAG,"Alive screen-brightness ref observe failed: "+error);}
        }
    }

    private static Object findRequest(List<Object> args){
        if(args==null)return null;
        for(Object arg:args){
            if(arg==null||arg instanceof Number||arg instanceof Boolean||arg instanceof String)continue;
            Class<?> type=arg.getClass();
            if(NOT_A_REQUEST.contains(type.getName()))continue;
            Field cached=policy;
            if(cached!=null&&cached.getDeclaringClass().isAssignableFrom(type))return arg;
            for(Class<?> walk=type;walk!=null;walk=walk.getSuperclass()){
                try{Field found=walk.getDeclaredField("policy");found.setAccessible(true);policy=found;return arg;}
                catch(NoSuchFieldException missing){/* walk up the hierarchy */}
            }
            NOT_A_REQUEST.add(type.getName());
        }
        return null;
    }
}
