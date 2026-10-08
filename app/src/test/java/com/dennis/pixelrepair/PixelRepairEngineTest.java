package com.dennis.pixelrepair;
import static org.junit.Assert.*;
import java.util.Arrays;
import org.junit.Test;

public class PixelRepairEngineTest {
    @Test public void flatImageUnchanged() {
        int[] image = new int[49];
        Arrays.fill(image, 0xff777777);
        PixelRepairEngine.Result output = PixelRepairEngine.repair(image, 7, 7, 50);
        assertEquals(0, output.changedPixels);
        assertArrayEquals(image, output.pixels);
    }
    @Test public void isolatedHotPixelCorrected() {
        int[] image = new int[49];
        Arrays.fill(image, 0xff505050);
        image[24] = 0xffffffff;
        PixelRepairEngine.Result output = PixelRepairEngine.repair(image, 7, 7, 70);
        assertTrue(output.changedPixels > 0);
        assertTrue((output.pixels[24] & 255) < 140);
        assertEquals(0xffffffff, image[24]); // input preserved
    }
    @Test public void isolatedChromaSpeckCorrected() {
        int[] image = new int[49];
        Arrays.fill(image, 0xff808080);
        image[24] = 0xffff0080; // saturated isolated colour
        PixelRepairEngine.Result output = PixelRepairEngine.repair(image, 7, 7, 80);
        assertTrue(output.changedPixels >= 1);
        assertNotEquals(image[24], output.pixels[24]);
    }
    @Test public void hardEdgeRemainsIntact() {
        int[] image = new int[81];
        for (int y = 0; y < 9; y++) for (int x = 0; x < 9; x++)
            image[y * 9 + x] = x < 4 ? 0xff202020 : 0xffdddddd;
        PixelRepairEngine.Result output = PixelRepairEngine.repair(image, 9, 9, 100);
        assertArrayEquals(image, output.pixels);
    }
}
