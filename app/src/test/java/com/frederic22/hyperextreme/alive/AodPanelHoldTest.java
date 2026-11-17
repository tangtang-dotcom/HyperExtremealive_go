package com.frederic22.hyperextreme.alive;

import org.junit.Test;
import static org.junit.Assert.*;

public class AodPanelHoldTest {
    @Test public void levelZeroReturnsTheStockDozeValue(){
        AodPanelHold hold=new AodPanelHold();
        assertEquals(.02f,hold.brightness(.02f,0),0);
        assertEquals(.013f,hold.brightness(.013f,0),0);
    }
    @Test public void darkEndUsesTheLargeMultiplier(){
        AodPanelHold hold=new AodPanelHold();
        assertEquals(.0137f*(1f+1.8f),hold.brightness(.0137f,1),1e-6);
        assertEquals(.013708094f*(1f+9f),hold.brightness(.013708094f,5),1e-5);
    }
    @Test public void brightEndUsesTheSmallMultiplierUpToThePanelCeiling(){
        AodPanelHold hold=new AodPanelHold();
        assertEquals(.3f*(1f+0.30f*2.5f),hold.brightness(.3f,2.5f),1e-4);
        assertEquals(1f,hold.brightness(.6f,5),0); // .6 * x2.5 -> clamped to the panel ceiling
    }
    @Test public void midRangeBridgesMonotonicallyWithoutSteps(){
        AodPanelHold hold=new AodPanelHold();
        assertEquals(.02f*(1f+9f),hold.brightness(.02f,5),1e-5); // left edge == dark end
        assertEquals(.15f*(1f+0.30f*5f),hold.brightness(.15f,5),1e-5); // right edge == bright end
        float previous=-1;
        for(float f=.02f;f<=.15f;f+=.005f){
            float out=hold.brightness(f,5);
            assertTrue(out>=previous);previous=out;
        }
    }
    @Test public void invalidRequestsRemainNative(){
        AodPanelHold hold=new AodPanelHold();
        for(float invalid:new float[]{0,-1,2,Float.POSITIVE_INFINITY,Float.NaN})
            assertEquals(invalid,hold.brightness(invalid,5),0);
    }
    @Test public void scopeIncludesAnimatedAodButExcludesWakeOffOtherDisplaysAndWallpapers(){
        for(int state:new int[]{2,3,4})assertTrue(AodPanelHold.eligible(true,true,false,0,1,state));
        assertFalse(AodPanelHold.eligible(false,true,false,0,1,2));
        assertFalse(AodPanelHold.eligible(true,false,false,0,1,2));
        assertFalse(AodPanelHold.eligible(true,true,true,0,1,3));
        assertFalse(AodPanelHold.eligible(true,true,false,1,1,2));
        for(int policy:new int[]{0,2,3})assertFalse(AodPanelHold.eligible(true,true,false,0,policy,2));
        for(int state:new int[]{0,1,5,6})assertFalse(AodPanelHold.eligible(true,true,false,0,1,state));
    }
}
