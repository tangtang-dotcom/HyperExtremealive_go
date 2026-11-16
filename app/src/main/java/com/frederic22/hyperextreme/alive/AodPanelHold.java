package com.frederic22.hyperextreme.alive;

/**
 * Adds a fixed absolute brightness step to the AOD (doze) panel brightness instead of
 * scaling it by ambient. The doze brightness is a 0..1 brightness fraction and the panel
 * maps it roughly linearly to backlight DBV (0..~16383), so a fixed DBV step is a fixed
 * absolute lift that does not depend on ambient light: a dim AOD and a brighter AOD both
 * gain roughly the same, as long as they stay under the normal-screen reference.
 *
 * Level 1..5 adds +100..+500 DBV (every +1.0 on the slider = +100 DBV); level 0 is a no-op.
 */
final class AodPanelHold {
    // Backlight register full scale minus one (~16383-1); turns a DBV step into a
    // brightness-fraction step (brightness -> DBV is ~linear on this display).
    private static final float DBV_MAX = 16382f;
    // Each +1.0 on the slider adds this many DBV (level 5 -> +500 DBV).
    private static final float DBV_PER_LEVEL = 100f;

    // Stateless; kept for API parity.
    void clear(){}

    static boolean valid(float value){return Float.isFinite(value)&&value>0&&value<=1;}

    float brightness(float requested,float normal,float level,boolean eligible,float nightScale){
        if(!eligible||AodBrightness.normalize(level)==0)return requested;
        if(!valid(requested))return requested;
        float addFrac=DBV_PER_LEVEL*AodBrightness.normalize(level)*nightScale/DBV_MAX; // fixed absolute step
        float target=requested+addFrac;
        float cap=valid(normal)?Math.min(1f,normal):1f; // never exceed the normal-screen doze reference
        if(target>cap)target=cap;
        return target; // additive: never dimmer than the stock value
    }

    static boolean eligible(boolean owned,boolean fullAod,boolean interactive,int display,int policy,int state){
        // Full AOD initially uses STATE_ON, then DOZE/DOZE_SUSPEND; STATE_OFF is final.
        return owned&&fullAod&&!interactive&&display==0&&policy==1&&(state==2||state==3||state==4);
    }
}
