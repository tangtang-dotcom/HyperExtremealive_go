package com.frederic22.hyperextreme.alive;

/**
 * Adds a fixed absolute brightness step (in backlight DBV) on top of the live, ambient-driven
 * doze brightness. The offset is a user-chosen amount (level 1..5 -> +100..+500 DBV), applied
 * in real time every time the display strategy recomputes brightness, so AOD keeps tracking
 * ambient light while being uniformly lifted.
 *
 * No soft ceiling: only the panel's physical range (0..1) is enforced. You dial brightness
 * with the level yourself; higher levels can exceed the normal-screen reference on purpose.
 */
final class AodPanelHold {
    // Backlight register full scale minus one (~16383-1); turns a DBV step into a brightness fraction.
    private static final float DBV_MAX = 16382f;
    // Each +1.0 on the slider adds this many DBV (level 5 -> +500 DBV).
    private static final float DBV_PER_LEVEL = 100f;

    // Stateless; kept for API parity.
    void clear(){}

    static boolean valid(float value){return Float.isFinite(value)&&value>0&&value<=1;}

    float brightness(float requested,float normal,float level,boolean eligible){
        if(!eligible||AodBrightness.normalize(level)==0)return requested;
        if(!valid(requested))return requested;
        float addFrac=DBV_PER_LEVEL*AodBrightness.normalize(level)/DBV_MAX; // fixed DBV step on the live ambient value
        float out=requested+addFrac;
        if(out>1f)out=1f; // only the panel's physical ceiling; no soft cap
        return out;
    }

    static boolean eligible(boolean owned,boolean fullAod,boolean interactive,int display,int policy,int state){
        // Full AOD initially uses STATE_ON, then DOZE/DOZE_SUSPEND; STATE_OFF is final.
        return owned&&fullAod&&!interactive&&display==0&&policy==1&&(state==2||state==3||state==4);
    }
}
