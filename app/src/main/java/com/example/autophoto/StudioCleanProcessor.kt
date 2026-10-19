package com.example.autophoto

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Experimental restoration stage, separate from and AFTER the approved
 * Polished neural LUT engine. Trained DnCNN (KAIR color blind) is run
 * on at most 12 noisy 256x256 regions; only its bounded luminance residual
 * is merged, protecting original Polished chroma, skin, edges and petals.
 * Returns the original Polished result on any runtime/model failure.
 */
object StudioCleanProcessor {
    data class Result(
        val bitmap: Bitmap,
        val polished: PolishedAIProcessor.Result,
        val appliedTiles: Int,
        val examinedTiles: Int,
        val estimatedNoise: Float,
        val aiUsed: Boolean,
        val message: String
    )
    private const val TILE=256
    private const val STRIDE=224
    private const val MAX_TILES=12
    private data class Tile(val x:Int,val y:Int,val score:Float)
    private fun luminance(p:Int):Float =
        .2126f*((p shr 16) and 255)+.7152f*((p shr 8) and 255)+
        .0722f*(p and 255)

    private fun starts(length:Int):IntArray {
        if(length<=TILE)return intArrayOf(0)
        val list=ArrayList<Int>()
        var a=0
        while(a<length-TILE){list.add(a);a+=STRIDE}
        if(list.isEmpty() || list.last()!=length-TILE)list.add(length-TILE)
        return list.toIntArray()
    }

    // Estimate noise from 4-neighbour highpass responses in smooth areas.
    // Structured grass/hair/flower filaments are excluded by gradient gates.
    private fun score(src:Bitmap,ox:Int,oy:Int):Float {
        val p=IntArray(TILE*TILE)
        src.getPixels(p,0,TILE,ox,oy,TILE,TILE)
        var total=0f;var count=0
        for(y in 3 until TILE-3 step 5)
            for(x in 3 until TILE-3 step 5){
                val i=y*TILE+x
                val c=luminance(p[i])
                if(c<15f || c>243f)continue
                val l=luminance(p[i-1]);val r=luminance(p[i+1])
                val t=luminance(p[i-TILE]);val b=luminance(p[i+TILE])
                if(abs(l-r)+abs(t-b)>25f)continue
                total+=abs(4f*c-l-r-t-b)*.25f
                count++
            }
        return if(count<120)0f else total/count
    }

    fun enhance(context:Context,source:Bitmap,progress:(Int)->Unit):Result {
        // The exact approved v0.4.6 Polished code is called verbatim.
        val polished=PolishedAIProcessor.enhance(
            context,source,PolishedAIProcessor.Style.POLISHED
        ){p->progress((p*.55f).roundToInt().coerceIn(0,55))}
        val width=source.width;val height=source.height
        if(width<TILE || height<TILE)
            return Result(polished.bitmap,polished,0,0,0f,false,
                "Small image: Polished unchanged")
        val all=ArrayList<Tile>()
        for(y in starts(height)) for(x in starts(width))
            all.add(Tile(x,y,score(source,x,y)))
        val mean=if(all.isEmpty())0f else all.map{it.score}.average().toFloat()
        val selected=all.filter{it.score>=2.35f}
            .sortedByDescending{it.score}.take(MAX_TILES)
        progress(60)
        if(selected.isEmpty())
            return Result(polished.bitmap,polished,0,all.size,mean,false,
                "Clean scene: neural restoration skipped")
        val bytes=try{context.assets.open("studio_dncnn_color_blind.onnx")
            .use{it.readBytes()}}catch(e:Exception){
            return Result(polished.bitmap,polished,0,all.size,mean,false,
                "Restoration asset unavailable: Polished retained")
        }
        val env=OrtEnvironment.getEnvironment()
        var composite:Bitmap?=null
        var completed=0
        try {
            OrtSession.SessionOptions().use{options ->
                options.setIntraOpNumThreads(2)
                env.createSession(bytes,options).use{session ->
                    val name=session.inputNames.first()
                    val original=IntArray(TILE*TILE)
                    val target=IntArray(TILE*TILE)
                    val predicted=FloatArray(3*TILE*TILE)
                    val samples=ByteBuffer.allocateDirect(3*TILE*TILE*4)
                        .order(ByteOrder.nativeOrder()).asFloatBuffer()
                    for((index,tile) in selected.withIndex()){
                        source.getPixels(original,0,TILE,tile.x,tile.y,TILE,TILE)
                        samples.clear()
                        for(c in 0..2) for(p in original)
                            samples.put(((p shr (16-8*c)) and 255)/255f)
                        samples.rewind()
                        OnnxTensor.createTensor(env,samples,
                            longArrayOf(1L,3L,TILE.toLong(),TILE.toLong())).use{input ->
                            session.run(mapOf(name to input)).use{output ->
                                val prediction=(output[0] as OnnxTensor).floatBuffer
                                prediction.rewind()
                                prediction.get(predicted)
                            }
                        }
                        if(!predicted.all{it.isFinite()})
                            error("Non-finite neural result")
                        if(composite==null)
                            composite=polished.bitmap.copy(
                                Bitmap.Config.ARGB_8888,true)
                        val bmp=composite ?: error("Cannot allocate output")
                        bmp.getPixels(target,0,TILE,tile.x,tile.y,TILE,TILE)
                        val strength=(.14f+.036f*(tile.score-2.35f))
                            .coerceIn(.14f,.38f)
                        for(py in 0 until TILE)for(px in 0 until TILE){
                            val i=py*TILE+px
                            val o=original[i];val q=target[i]
                            val denY=255f*(.2126f*predicted[i]+
                                .7152f*predicted[TILE*TILE+i]+
                                .0722f*predicted[2*TILE*TILE+i])
                            val delta=(denY-luminance(o)).coerceIn(-9f,9f)
                            val edge=abs(luminance(o)-luminance(
                                original[py*TILE+min(255,px+1)]))+
                                abs(luminance(o)-luminance(
                                    original[min(255,py+1)*TILE+px]))
                            val edgeGuard=(1f-edge/34f).coerceIn(.08f,1f)
                            val r=(o shr 16) and 255
                            val g=(o shr 8) and 255
                            val b=o and 255
                            val flower=if(r>g*1.20f && r>b*1.10f).12f else 1f
                            val skin=if(r>g*1.06f && g>b*1.07f && r>85).55f else 1f
                            val highlights=if(max(r,max(g,b))>238).18f else 1f
                            val feather=min(1f,min(
                                min(px,255-px)/20f,
                                min(py,255-py)/20f)).coerceIn(0f,1f)
                            val adjustment=delta*strength*edgeGuard*
                                flower*skin*highlights*feather
                            val rr=(((q shr 16)and 255)+adjustment)
                                .roundToInt().coerceIn(0,255)
                            val gg=(((q shr 8)and 255)+adjustment)
                                .roundToInt().coerceIn(0,255)
                            val bb=((q and 255)+adjustment)
                                .roundToInt().coerceIn(0,255)
                            target[i]=(q and -0x1000000) or
                                (rr shl 16) or (gg shl 8) or bb
                        }
                        bmp.setPixels(target,0,TILE,tile.x,tile.y,TILE,TILE)
                        completed++
                        progress(60+(index+1)*39/selected.size)
                    }
                }
            }
        }catch(e:Exception){
            composite?.recycle()
            return Result(polished.bitmap,polished,0,all.size,mean,false,
                "Restoration unavailable ("+e.javaClass.simpleName+
                "): Polished retained")
        }
        progress(100)
        return Result(composite ?: polished.bitmap,polished,completed,all.size,
            mean,true,"Pretrained DnCNN • guarded luminance-only restoration")
    }
}
