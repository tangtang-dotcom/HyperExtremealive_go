package com.frederic22.hyperextreme.alive;

/**
 * Adaptive AOD lift: dim AOD gets a large multiplier, already-bright AOD gets a small one.
 * The input is the live ambient-computed doze brightness (real-time), so this follows ambient
 * every frame. Slider level 1..5 scales the aggressiveness:
 *   - dark end multiplier   = 1 + 5.0*level   (level 5 -> x26, level 1 -> x6)
 *   - bright end multiplier = 1 + 1.4*level   (level 5 -> x8, level 1 -> x2.4)
 * There is deliberately NO lockscreen cap here: the AOD keeps its full range. The AOD ->
 * lockscreen transition flash is handled at the backlight-ramp level instead (WakeGlide).
 */
final class AodPanelHold {
    // Below DARK_R the full dark multiplier applies; above BRIGHT_R the small bright multiplier does.
    private static final float DARK_R = 0.02f;
    private static final float BRIGHT_R = 0.15f;

    static boolean valid(float value){return Float.isFinite(value)&&value>0&&value<=1;}

    float brightness(float requested,float level){
        if(AodBrightness.normalize(level)==0)return requested;
        if(!valid(requested))return requested;
        float lv=AodBrightness.normalize(level);
        float lowMax=AodBrightness.aodDarkFactor(lv);    // dark-end multiplier (lv5 -> x26)
        float highMin=AodBrightness.aodBrightFactor(lv);  // bright-end multiplier (lv5 -> x8)
        float out;
        if(requested<=DARK_R){
            out=requested*lowMax;                       // dim: big boost
        }else if(requested>=BRIGHT_R){
            out=requested*highMin;                      // already bright: gentle boost
        }else{
            float oLo=DARK_R*lowMax,oHi=BRIGHT_R*highMin;
            float u=(requested-DARK_R)/(BRIGHT_R-DARK_R);
            out=oLo+(oHi-oLo)*u;                        // smooth monotonic bridge
        }
        if(out>1f)out=1f;               // panel physical ceiling
        if(out<requested)out=requested; // monotonic: never dimmer than the stock doze value
        return out;
    }

    static boolean eligible(boolean owned,boolean fullAod,boolean interactive,int display,int policy,int state){
        // Full AOD initially uses STATE_ON, then DOZE/DOZE_SUSPEND; STATE_OFF is final.
        return owned&&fullAod&&!interactive&&display==0&&policy==1&&(state==2||state==3||state==4);
    }
}
