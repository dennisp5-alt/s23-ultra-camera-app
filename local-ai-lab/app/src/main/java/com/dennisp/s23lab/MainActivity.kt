package com.dennisp.s23lab

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.os.BatteryManager
import android.os.Debug
import android.app.ActivityManager
import android.content.Context
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.ai.edge.litertlm.*
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.*

private val bg=Color(0xFF0D1422)
private val panel=Color(0xFF182334)
private val accent=Color(0xFF67E9D1)
class MainActivity:ComponentActivity(){
 private val executor=Executors.newSingleThreadExecutor()
 private val aiScope=CoroutineScope(SupervisorJob()+executor.asCoroutineDispatcher())
 private var engine:Engine?=null
 private var conversation:Conversation?=null
 private val file by lazy {File(filesDir,"model.litertlm")}
 private val modelAvailable=mutableStateOf(false)
 private val loaded=mutableStateOf(false)
 private val busy=mutableStateOf(false)
 private val info=mutableStateOf("No model loaded")
 private val reply=mutableStateOf("")
 private val backend=mutableStateOf("")
 private val modelLoadMs=mutableStateOf(0L)
 private val cpuStats=mutableStateOf("")
 private val gpuStats=mutableStateOf("")
 private var cpuRate=0.0
 private var gpuRate=0.0
 private var cpuPrompt=""
 private var gpuPrompt=""
 private val hardware=mutableStateOf("")
 private val cpuBench=mutableStateListOf<BenchSample>()
 private val gpuBench=mutableStateListOf<BenchSample>()
 private val enduranceStatus=mutableStateOf("")
 @Volatile private var stopAfterCurrent=false
 private val fixedPrompt="In one sentence, explain what offline AI does on a phone."
 private data class BenchSample(val durationMs:Long,val firstMs:Long,val length:Int,
  val pssMiB:Int,val tempC:Double){
  val estimate:Double get()=(length/4.0)/(durationMs.coerceAtLeast(1)/1000.0)
 }
 private fun median(numbers:List<Double>):Double{
  if(numbers.isEmpty())return 0.0
  val ordered=numbers.sorted()
  return if(ordered.size%2==1)ordered[ordered.size/2]
   else (ordered[ordered.size/2-1]+ordered[ordered.size/2])/2
 }
 private fun benchmarkSummary(runs:List<BenchSample>):String{
  if(runs.isEmpty())return "Not measured"
  val rate=median(runs.map{it.estimate})
  val start=median(runs.map{it.firstMs.toDouble()})/1000.0
  val total=median(runs.map{it.durationMs.toDouble()})/1000.0
  return runs.size.toString()+" runs | first output "+String.format(java.util.Locale.US,"%.2f",start)+
   " s | total "+String.format(java.util.Locale.US,"%.2f",total)+
   " s | ~"+String.format(java.util.Locale.US,"%.1f",rate)+
   " estimated tokens/s | peak app PSS "+runs.maxOf{it.pssMiB}+" MiB"
 }
 private fun batteryValues():Triple<Int,Double,Long>{
  val b=registerReceiver(null,IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
  val level=b?.getIntExtra(BatteryManager.EXTRA_LEVEL,-1)?:-1
  val scale=b?.getIntExtra(BatteryManager.EXTRA_SCALE,100)?:100
  val temp=b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,-1)?:-1
  val am=getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
  val memory=ActivityManager.MemoryInfo().also{am.getMemoryInfo(it)}
  return Triple(if(level>=0&&scale>0)level*100/scale else -1,
   if(temp>=0)temp/10.0 else -1.0,memory.availMem/1048576L)
 }

 private fun deviceInfo():String {
  val am=getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
  val m=ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
  val p=Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
  val b=registerReceiver(null, IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
  val level=b?.getIntExtra(BatteryManager.EXTRA_LEVEL,-1)?:-1
  val scale=b?.getIntExtra(BatteryManager.EXTRA_SCALE,100)?:100
  val temp=b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,-1)?:-1
  val pct=if(level>=0 && scale>0) (level*100/scale).toString()+"%" else "unknown"
  val degrees=if(temp>=0) String.format(java.util.Locale.US,"%.1f",temp/10.0)+"°C" else "unknown"
  return "App PSS: "+(p.totalPss/1024)+" MiB | Free RAM: "+(m.availMem/(1024*1024))+
    " MiB\nBattery: "+pct+" | Battery temperature: "+degrees+
    "\nBattery temperature is not CPU/GPU temperature. No app-only energy estimate."
 }

 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState)
  modelAvailable.value=file.exists()
  hardware.value=deviceInfo()
  setContent {
   MaterialTheme(colorScheme=darkColorScheme(primary=accent,onPrimary=bg,background=bg,onBackground=Color.White,surface=panel,onSurface=Color.White,onSurfaceVariant=Color(0xFFB8C8D9))){
    var input by remember { mutableStateOf("dew point 21 C RH 81%") }
    var answer by remember { mutableStateOf("") }
    var benchmark by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("Explain offline AI on a Samsung phone in 3 sentences.") }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
     if(uri!=null) importModel(uri)
    }
    Column(
     modifier=Modifier.fillMaxSize().background(bg).verticalScroll(rememberScrollState()).padding(16.dp),
     verticalArrangement=Arrangement.spacedBy(14.dp)
    ){
     Text("S23 / LOCAL AI LAB",color=accent)
     Text("Intelligence, on device.",color=Color.White,style=MaterialTheme.typography.headlineMedium)
     Text("v0.3 • PRIVATE • OFFLINE • CPU / GPU",color=Color(0xFFAABCD0),style=MaterialTheme.typography.bodySmall)
     Card(colors=CardDefaults.cardColors(containerColor=panel)){
      Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
       Text("01 / Reusable reasoning",style=MaterialTheme.typography.titleLarge)
       OutlinedTextField(value=input,onValueChange={input=it},label={Text("Instruction")},modifier=Modifier.fillMaxWidth())
       Button(onClick={
        val t=System.nanoTime()
        val a=OfflineTools.run(input)
        val micros=(System.nanoTime()-t)/1000.0
        answer=if(a==null)"No validated tool matches. No answer was invented."
               else a.output+" • "+String.format("%.1f",micros)+" μs CPU"
       }){Text("Run offline")}
       if(answer.isNotBlank())Text(answer,color=accent)
       Text("Try: convert 25 C to F | 15% of 240 | exposure +1 EV",style=MaterialTheme.typography.bodySmall)
      }
     }
     Card(colors=CardDefaults.cardColors(containerColor=panel)){
      Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
       Text("02 / CPU benchmark",style=MaterialTheme.typography.titleLarge)
       OutlinedButton(onClick={
        CoroutineScope(Dispatchers.Default).launch{
         val phrases=listOf("convert 25 C to F","15% of 240","dew point 21 C RH 81%","exposure +1 EV")
         val samples=LongArray(12000)
         for(i in samples.indices){
          val t=System.nanoTime()
          OfflineTools.run(phrases[i%phrases.size])
          samples[i]=System.nanoTime()-t
         }
         samples.sort()
         benchmark="12,000 CPU calls | median "+String.format("%.4f",samples[6000]/1e6)+" ms | p95 "+String.format("%.4f",samples[11400]/1e6)+" ms"
        }
       }){Text("Benchmark")}
       Text(benchmark.ifBlank{"No measurement yet."})
      }
     }
     Card(colors=CardDefaults.cardColors(containerColor=panel)){
      Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
       Text("03 / Offline AI model",style=MaterialTheme.typography.titleLarge)
       Text(if(modelAvailable.value)"Model imported" else "Choose a compatible .litertlm model")
       OutlinedButton(onClick={picker.launch(arrayOf("*/*"))},enabled=!loaded.value&&!busy.value){Text("Import model")}
       Button(onClick={loadModel(true,true)},enabled=modelAvailable.value&&!busy.value){
        Text("Auto GPU → CPU fallback")
       }
       Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
        OutlinedButton(onClick={loadModel(false,false)},enabled=modelAvailable.value&&!busy.value){Text("Load CPU")}
        OutlinedButton(onClick={loadModel(true,false)},enabled=modelAvailable.value&&!busy.value){Text("Load GPU")}
        OutlinedButton(onClick={unloadModel()},enabled=loaded.value&&!busy.value){Text("Unload")}
       }
       Text(info.value,style=MaterialTheme.typography.bodySmall)
       OutlinedTextField(value=prompt,onValueChange={prompt=it},modifier=Modifier.fillMaxWidth(),label={Text("Ask the model")},minLines=2)
       Button(onClick={askModel(prompt)},enabled=(loaded.value||OfflineTools.run(prompt)!=null)&&!busy.value){Text(if(busy.value)"Working…" else "Run smart task")}
       if(reply.value.isNotBlank())Text(reply.value)
       Text("Known calculations use validated CPU code without invoking AI. NPU not enabled.",
        style=MaterialTheme.typography.bodySmall,color=Color(0xFFAABCD0))
      }
     }
     Card(colors=CardDefaults.cardColors(containerColor=panel)){
      Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
       Text("04 / AI performance",style=MaterialTheme.typography.titleLarge)
       Text("CPU",color=accent)
       Text(cpuStats.value.ifBlank{"No generation benchmark yet."})
       Text("GPU",color=accent)
       Text(gpuStats.value.ifBlank{"No generation benchmark yet."})
       if(cpuRate>0 && gpuRate>0 && cpuPrompt==gpuPrompt)
        Text("Last same-prompt GPU / CPU estimated throughput: "+
         String.format(java.util.Locale.US,"%.2f",gpuRate/cpuRate)+"×",color=accent)
       HorizontalDivider()
       Text("Repeated tests (fixed prompt, fresh conversation)",color=accent)
       Button(onClick={startBench(3,false)},enabled=loaded.value&&!busy.value){
        Text("Run 3 repeated tests")
       }
       OutlinedButton(onClick={startBench(20,true)},enabled=loaded.value&&!busy.value){
        Text("2-minute endurance (max 20 replies)")
       }
       if(busy.value)
        OutlinedButton(onClick={stopAfterCurrent=true}){Text("Stop after current reply")}
       if(enduranceStatus.value.isNotBlank())Text(enduranceStatus.value,color=accent)
       Text("Repeated CPU: "+benchmarkSummary(cpuBench))
       Text("Repeated GPU: "+benchmarkSummary(gpuBench))
       if(cpuBench.isNotEmpty()&&gpuBench.isNotEmpty()){
        val cRate=median(cpuBench.map{it.estimate})
        val gRate=median(gpuBench.map{it.estimate})
        if(cRate>0)Text("Median GPU / CPU estimated throughput: "+
          String.format(java.util.Locale.US,"%.2f",gRate/cRate)+"×",color=accent)
       }
       Text("Estimated tokens/s = output characters ÷ 4 ÷ seconds. Not model tokenizer counts. First output = first nonempty chunk. For comparison, use exactly the same prompt.",
        color=Color(0xFFAABCD0),style=MaterialTheme.typography.bodySmall)
      }
     }
     Card(colors=CardDefaults.cardColors(containerColor=panel)){
      Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
       Text("05 / Device health",style=MaterialTheme.typography.titleLarge)
       Text(hardware.value)
       OutlinedButton(onClick={hardware.value=deviceInfo()}){Text("Refresh")}
      }
     }
     Card(colors=CardDefaults.cardColors(containerColor=panel)){
      Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
       Text("06 / Snapdragon NPU",style=MaterialTheme.typography.titleLarge)
       Text("Not enabled: the existing Qwen model and LiteRT library are confirmed for CPU/GPU, "+
        "but Snapdragon-specific compiled model support and NPU native libraries are not yet verified. "+
        "No unsupported accelerator will be installed.",color=Color(0xFFAABCD0))
      }
     }
     Text("v0.3 • Side-by-side install • import model again • no internet permission or telemetry.",
       color=Color(0xFFAABCD0),style=MaterialTheme.typography.bodySmall)
    }
   }
  }
 }
 private fun importModel(uri:Uri){
  if(loaded.value||busy.value)return
  busy.value=true
  info.value="Importing…"
  CoroutineScope(Dispatchers.IO).launch{
   val temp=File(filesDir,"model.litertlm.tmp")
   try{
    contentResolver.openInputStream(uri).use{source->
     requireNotNull(source)
     temp.outputStream().use{dest-> source.copyTo(dest,131072)}
    }
    require(temp.length()>2_000_000)
    if(file.exists())check(file.delete())
    check(temp.renameTo(file))
    modelAvailable.value=true
    info.value="Model stored privately. Load CPU first."
   }catch(e:Exception){ info.value="Import failed: "+e.message}
   finally{temp.delete();busy.value=false}
  }
 }
 private fun closeModel(){
  try{conversation?.close()}catch(_:Exception){}
  conversation=null
  try{engine?.close()}catch(_:Exception){}
  engine=null
  loaded.value=false
  backend.value=""
 }
 private suspend fun initializeModel(gpu:Boolean){
  closeModel()
  val started=SystemClock.elapsedRealtimeNanos()
  val candidate=Engine(EngineConfig(
   modelPath=file.absolutePath,
   backend=if(gpu)Backend.GPU()else Backend.CPU(),
   cacheDir=cacheDir.path))
  try{
   candidate.initialize()
   engine=candidate
   conversation=candidate.createConversation(ConversationConfig(
    systemInstruction=Contents.of("You are a private offline assistant. Be accurate, "+
     "state uncertainty, and do not claim you learn during inference.")))
   backend.value=if(gpu)"GPU" else "CPU"
   modelLoadMs.value=(SystemClock.elapsedRealtimeNanos()-started)/1000000L
   loaded.value=true
   info.value="Ready on "+backend.value+" • load "+modelLoadMs.value+" ms"
  }catch(e:Exception){
   try{candidate.close()}catch(_:Exception){}
   throw e
  }
 }
 private fun loadModel(gpu:Boolean, auto:Boolean=false){
  if(!modelAvailable.value||busy.value)return
  busy.value=true
  info.value="Loading model…"
  aiScope.launch{
   try{
    try{
     initializeModel(gpu)
    }catch(e:Exception){
     if(!gpu||!auto)throw e
     info.value="GPU initialization failed, testing CPU fallback…"
     initializeModel(false)
     info.value="Ready on CPU (automatic GPU fallback)"
    }
    hardware.value=deviceInfo()
   }catch(e:Exception){
    closeModel()
    info.value="Load failed: "+e.message
   }finally{busy.value=false}
  }
 }
 private fun unloadModel(){
  if(busy.value)return
  busy.value=true
  aiScope.launch{
   try{
    closeModel()
    info.value="Model unloaded; native inference resources released."
    hardware.value=deviceInfo()
   }finally{busy.value=false}
  }
 }
 private fun startBench(maxRuns:Int, endurance:Boolean){
  if(!loaded.value||busy.value)return
  busy.value=true
  stopAfterCurrent=false
  enduranceStatus.value="Starting repeated test on "+backend.value+"…"
  aiScope.launch{
   val started=SystemClock.elapsedRealtime()
   var finished=0
   try{
    for(i in 0 until maxRuns){
     if(stopAfterCurrent)break
     val elapsed=SystemClock.elapsedRealtime()-started
     if(endurance&&elapsed>=120000L)break
     val (percent,temp,free)=batteryValues()
     if((percent>=0&&percent<15)||(temp>=43.0)||(free<500L)){
      enduranceStatus.value="Test stopped at safety limit: "+
       "battery "+percent+"%, battery sensor "+temp+"°C, free RAM "+free+" MiB"
      break
     }
     enduranceStatus.value="Running "+(i+1)+"/"+maxRuns+
      " on "+backend.value+" • elapsed "+(elapsed/1000)+" seconds"
     val fresh=checkNotNull(engine).createConversation(ConversationConfig(
      systemInstruction=Contents.of("Answer in one brief factual sentence. "+
       "Do not claim online access or self-training.")))
     try{
      val start=SystemClock.elapsedRealtimeNanos()
      var first=0L
      var chars=0
      fresh.sendMessageAsync(fixedPrompt).collect{chunk->
       val t=chunk.toString()
       if(t.isNotEmpty()){
        if(first==0L)first=SystemClock.elapsedRealtimeNanos()
        chars+=t.length
       }
      }
      val end=SystemClock.elapsedRealtimeNanos()
      val runtime=((end-start)/1000000L).coerceAtLeast(1L)
      val pss=Debug.MemoryInfo().also{Debug.getMemoryInfo(it)}.totalPss/1024
      val reading=batteryValues().second
      val sample=BenchSample(runtime,if(first>0)(first-start)/1000000L else runtime,
       chars,pss,reading)
      if(backend.value=="GPU")gpuBench.add(sample) else cpuBench.add(sample)
      finished++
      hardware.value=deviceInfo()
     }finally{fresh.close()}
    }
    enduranceStatus.value="Finished "+finished+" test(s) in "+
     String.format(java.util.Locale.US,"%.1f",(SystemClock.elapsedRealtime()-started)/1000.0)+
     " seconds"+if(stopAfterCurrent)" (stopped by user)" else ""
   }catch(e:Exception){
    enduranceStatus.value="Test halted after "+finished+" runs: "+e.message
   }finally{
    busy.value=false
    stopAfterCurrent=false
    hardware.value=deviceInfo()
   }
  }
 }
 private fun askModel(text:String){
  if(busy.value||text.isBlank())return
  val tool=OfflineTools.run(text)
  if(tool!=null){
   reply.value=tool.output
   info.value="Validated CPU tool • AI inference skipped • reusable solution"
   return
  }
  if(!loaded.value)return
  aiScope.launch{
   busy.value=true;reply.value=""
   val start=SystemClock.elapsedRealtimeNanos()
   var first=0L
   try{
    conversation?.sendMessageAsync(text)?.collect{ chunk->
     val s=chunk.toString()
     if(s.isNotEmpty()){
      if(first==0L)first=SystemClock.elapsedRealtimeNanos()
      reply.value+=s
     }
    }
    val end=SystemClock.elapsedRealtimeNanos()
    val elapsed=((end-start)/1000000L).coerceAtLeast(1L)
    val firstMs=if(first>0) (first-start)/1000000L else elapsed
    val estimate=(reply.value.length/4.0)/(elapsed/1000.0)
    val report="load "+modelLoadMs.value+" ms | first output "+firstMs+
     " ms | complete "+elapsed+" ms | chars "+reply.value.length+
     " | estimated "+String.format(java.util.Locale.US,"%.1f",estimate)+" tokens/s"
    if(backend.value=="GPU"){gpuStats.value=report;gpuRate=estimate;gpuPrompt=text}
    else {cpuStats.value=report;cpuRate=estimate;cpuPrompt=text}
    info.value="Generation completed on "+backend.value
    hardware.value=deviceInfo()
   }catch(e:Exception){reply.value="Error: "+e.message}
   finally{busy.value=false}
  }
 }
 override fun onDestroy(){
  stopAfterCurrent=true
  aiScope.launch{
   closeModel()
  }.invokeOnCompletion{aiScope.cancel();executor.shutdown()}
  super.onDestroy()
 }
}
