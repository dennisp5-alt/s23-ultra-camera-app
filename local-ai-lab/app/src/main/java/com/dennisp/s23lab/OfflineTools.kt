package com.dennisp.s23lab
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

object OfflineTools {
 data class Answer(val label:String, val output:String)
 private val num = "([+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+))"
 private val temp = Regex("^convert\\s+" + num + "\\s*([cCfF])\\s+to\\s+([cCfF])$", RegexOption.IGNORE_CASE)
 private val pct = Regex("^(?:what\\s+is\\s+)?" + num + "\\s*%\\s+of\\s+" + num + "\\??$", RegexOption.IGNORE_CASE)
 private val dew = Regex("^dew\\s*point\\s+" + num + "\\s*c\\s+(?:rh\\s*)?" + num + "\\s*%?$", RegexOption.IGNORE_CASE)
 private val ev = Regex("^(?:photo\\s+)?exposure\\s+" + num + "\\s*ev$", RegexOption.IGNORE_CASE)
 private fun f(x:Double) = String.format(Locale.US,"%.2f",x).trimEnd('0').trimEnd('.')
 fun run(raw:String):Answer? {
  val s=raw.trim().replace(Regex("\\s+")," ")
  temp.matchEntire(s)?.let {
   val x=it.groupValues[1].toDoubleOrNull()?:return null
   val a=it.groupValues[2].uppercase(Locale.ROOT);val b=it.groupValues[3].uppercase(Locale.ROOT)
   // Reject temperatures below absolute zero for the given unit (-273.15 °C, -459.67 °F).
   val floor=if(a=="C") -273.15 else -459.67
   if(!x.isFinite()||x<floor||x>10000.0) return null
   val y=if(a==b)x else if(a=="C") x*9/5+32 else (x-32)*5/9
   return Answer("Temperature", f(x)+"°"+a+" = "+f(y)+"°"+b)
  }
  pct.matchEntire(s)?.let {
   val x=it.groupValues[1].toDoubleOrNull()?:return null
   val y=it.groupValues[2].toDoubleOrNull()?:return null
   if(!x.isFinite()||!y.isFinite()||x !in -100000.0..100000.0||y !in -1e12..1e12)return null
   return Answer("Percentage",f(x)+"% of "+f(y)+" = "+f(x*y/100))
  }
  dew.matchEntire(s)?.let {
   val t=it.groupValues[1].toDoubleOrNull()?:return null
   val rh=it.groupValues[2].toDoubleOrNull()?:return null
   // Magnus constants 17.625 / 243.04 (Alduchov and Eskridge) are intended for about -40 to 50 °C.
   if(!t.isFinite()||!rh.isFinite()||t !in -40.0..50.0||rh !in 1.0..100.0) return null
   val g=ln(rh/100.0)+17.625*t/(243.04+t)
   val d=243.04*g/(17.625-g)
   return Answer("Dew point estimate",f(t)+"°C at "+f(rh)+"% RH → approximately "+f(d)+"°C")
  }
  ev.matchEntire(s)?.let {
   val x=it.groupValues[1].toDoubleOrNull()?:return null
   if(!x.isFinite()||x !in -10.0..10.0)return null
   return Answer("Photo exposure",f(x)+" EV → "+f(2.0.pow(x))+"× light")
  }
  return null
 }
}
