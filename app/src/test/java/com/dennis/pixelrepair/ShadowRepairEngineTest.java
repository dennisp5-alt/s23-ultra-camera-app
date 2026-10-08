package com.dennis.pixelrepair;
import static org.junit.Assert.*;
import java.util.Arrays;
import org.junit.Test;

public class ShadowRepairEngineTest {
    @Test public void balancedReducesIsolatedFineChromaInShadows() {
        int[] image=new int[121];Arrays.fill(image,0xff292929);
        image[60]=0xff392329; // small chroma error; below isolated defect threshold
        int[] output=image.clone();
        int n=ShadowRepairEngine.denoise(image,output,11,11,90,false);
        assertTrue("Expect localized chroma repair",n>0);
        assertNotEquals(image[60],output[60]);
        assertEquals(0xff292929,image[0]);
    }
    @Test public void daylightRemainsUnchanged() {
        int[] image=new int[121];Arrays.fill(image,0xffcccccc);
        image[60]=0xffd8c3c8;
        int[] output=image.clone();
        assertEquals(0,ShadowRepairEngine.denoise(image,output,11,11,100,false));
        assertArrayEquals(image,output);
    }
    @Test public void protectEdges() {
        int[] image=new int[121];
        for(int y=0;y<11;y++)for(int x=0;x<11;x++)
            image[y*11+x]=x<5?0xff202020:0xffa0a0a0;
        int[] output=image.clone();
        assertEquals(0,ShadowRepairEngine.denoise(image,output,11,11,100,true));
        assertArrayEquals(image,output);
    }

    @Test public void protectsDenseDarkFoliageTexture() {
        int[] image=new int[225];
        for (int y=0;y<15;y++) for(int x=0;x<15;x++)
            image[y*15+x]=((x+y)&1)==0?0xff282e24:0xff464c42;
        int[] output=image.clone();
        int edited=ShadowRepairEngine.denoise(image,output,15,15,100,true);
        assertEquals("Dense genuine texture should remain",0,edited);
        assertArrayEquals(image,output);
    }
    @Test public void shadowLabCorrectsFlatShadowChroma() {
        int[] image=new int[225];
        Arrays.fill(image,0xff292929);
        image[112]=0xff392329;
        int[] output=image.clone();
        int edited=ShadowRepairEngine.denoise(image,output,15,15,100,true);
        assertTrue(edited>0);
        assertNotEquals(image[112],output[112]);
    }
    @Test public void zeroSensitivityIsPassThrough() {
        int[] image=new int[49];Arrays.fill(image,0xff232323);
        image[24]=0xff392329;
        int[] output=image.clone();
        assertEquals(0,ShadowRepairEngine.denoise(image,output,7,7,0,true));
        assertArrayEquals(image,output);
    }
}
