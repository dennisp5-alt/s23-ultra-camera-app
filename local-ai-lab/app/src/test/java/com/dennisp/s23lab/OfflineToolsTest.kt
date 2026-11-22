package com.dennisp.s23lab
import org.junit.Assert.*
import org.junit.Test
class OfflineToolsTest {
 @Test fun conversion(){ assertEquals("25°C = 77°F",OfflineTools.run("convert 25 C to F")?.output) }
 @Test fun percentage(){ assertEquals("15% of 240 = 36",OfflineTools.run("15% of 240")?.output) }
 @Test fun saturation(){ assertTrue(OfflineTools.run("dew point 20 C RH 100%")?.output?.contains("20°C")==true) }
 @Test fun rejects(){assertNull(OfflineTools.run("dew point 20 C RH 120%"));assertNull(OfflineTools.run("delete my files"))}
 @Test fun belowAbsoluteZero(){
  assertNull(OfflineTools.run("convert -300 C to F"))
  assertEquals("-273.15°C = -459.67°F",OfflineTools.run("convert -273.15 C to F")?.output)
  assertNull(OfflineTools.run("convert -500 F to C"))
 }
 @Test fun dewPointRange(){
  assertNull(OfflineTools.run("dew point 55 C RH 50%"))
  assertNull(OfflineTools.run("dew point -42 C RH 50%"))
  assertTrue(OfflineTools.run("dew point 21 C RH 81%")?.output?.contains("approximately 17.6")==true)
 }
}
