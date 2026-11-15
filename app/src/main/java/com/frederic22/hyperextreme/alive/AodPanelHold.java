package com.frederic22.hyperextreme.alive;

/**
 * Scales the ambient-computed AOD panel brightness by a user factor instead of
 * freezing or clamping it.
 *
 * The doze brightness the display strategy computes already tracks ambient light,
 * so multiplying that live value keeps the responsiveness while making the whole
 * AOD uniformly brighter. The factor comes straight from the 0..5 setting:
 * level 0 = untouched, every +1.0 on the slider = +20% brightness (level 5 -> x2.0).
 */
final class AodPanelHold {
    // Each +1.0 on the slider adds +20%. Change this one constant to retune strength.
    private static final float STEP_GAIN = 0.20f;
    // Never exceed this multiple of the lock-screen (normal) doze brightness (anti burn-in / over-bright).
    private static final float CEIL_RATIO = 1.20f;

    // Stateless; kept for API parity.
    void clear(){}

    static boolean valid(float value){return Float.isFinite(value)&&value>0&&value<=1;}

    float brightness(float requested,float normal,float level,boolean eligible){
        if(!eligible||AodBrightness.normalize(level)==0)return requested;
        if(!valid(requested))return requested;
        float boost=1f+STEP_GAIN*AodBrightness.normalize(level); // 1.0 .. 2.0 for level 0..5
        float target=requested*boost;                             // follows ambient, scaled up
        float cap=valid(normal)?Math.min(1f,normal*CEIL_RATIO):1f;
        if(target>cap)target=cap;
        return target; // boost >= 1, so never dimmer than the stock value
    }

    static boolean eligible(boolean owned,boolean fullAod,boolean interactive,int display,int policy,int state){
        // Full AOD initially uses STATE_ON, then DOZE/DOZE_SUSPEND; STATE_OFF is final.
        return owned&&fullAod&&!interactive&&display==0&&policy==1&&(state==2||state==3||state==4);
    }
}
