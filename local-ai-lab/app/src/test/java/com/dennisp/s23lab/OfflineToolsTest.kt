package com.dennisp.s23lab
import org.junit.Assert.*
import org.junit.Test
class OfflineToolsTest {
 @Test fun conversion(){ assertEquals("25°C = 77°F",OfflineTools.run("convert 25 C to F")?.output) }
 @Test fun percentage(){ assertEquals("15% of 240 = 36",OfflineTools.run("15% of 240")?.output) }
 @Test fun saturation(){ assertTrue(OfflineTools.run("dew point 20 C RH 100%")?.output?.contains("20°C")==true) }
 @Test fun rejects(){assertNull(OfflineTools.run("dew point 20 C RH 120%"));assertNull(OfflineTools.run("delete my files"))}
}
