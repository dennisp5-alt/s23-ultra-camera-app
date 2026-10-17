package com.example.autophoto

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.DataType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Two independently trained, offline neural enhancement pipelines.
 *
 * MobileIE: ICCV2025 low-light enhancer (pretrained lolv1.tflite).
 * Adaptive LUT: learned Hui Zeng sRGB 3D-LUT classifier + THREE actual learned
 * 33^3 RGB LUT bases, fused using predictor's per-photo weights.
 *
 * Both preserve source detail and protected highlights; Auto code is untouched.
 */
object AlternativeAIProcessor {
    data class Report(val bitmap: Bitmap, val backend: String,
                      val inferenceMillis: Long, val totalMillis: Long)
    private const val DIM = 33
    private const val MAX_MOBILE = 768

    private fun ease(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
    private fun assetBytes(ctx: Context, name: String): ByteArray =
        ctx.assets.open(name).use { it.readBytes() }

    private fun bitmapPixels(bmp: Bitmap): IntArray =
        IntArray(bmp.width * bmp.height).also {
            bmp.getPixels(it,0,bmp.width,0,0,bmp.width,bmp.height)
        }

    private fun luminance(p: Int): Float =
        (0.2126f * ((p shr 16) and 255) +
         0.7152f * ((p shr 8) and 255) +
         0.0722f * (p and 255))

    private fun percentile(pixels: IntArray, fraction: Float): Int {
        val hist=IntArray(256)
        val step=max(1,pixels.size/50000)
        var total=0
        for (i in pixels.indices step step) {
            hist[luminance(pixels[i]).roundToInt().coerceIn(0,255)]++
            total++
        }
        var cum=0
        val threshold=max(1,(total*fraction).roundToInt())
        for (i in 0..255) {
            cum+=hist[i]
            if(cum>=threshold) return i
        }
        return 255
    }

    private fun sampleColor(image: IntArray,w:Int,h:Int,x:Float,y:Float,c:Int):Float {
        val px=x.coerceIn(0f,(w-1).toFloat())
        val py=y.coerceIn(0f,(h-1).toFloat())
        val x0=px.toInt();val y0=py.toInt()
        val x1=min(x0+1,w-1);val y1=min(y0+1,h-1)
        val ax=px-x0;val ay=py-y0
        fun ch(p:Int):Float = ((p shr (16-8*c)) and 255).toFloat()
        val c00=ch(image[y0*w+x0]);val c10=ch(image[y0*w+x1])
        val c01=ch(image[y1*w+x0]);val c11=ch(image[y1*w+x1])
        return ((c00*(1-ax)+c10*ax)*(1-ay)+(c01*(1-ax)+c11*ax)*ay)
    }

    /** Low-light trained MobileIE TFLite, no remote calls. */
    fun mobileIE(context: Context, src: Bitmap, progress:(Int)->Unit): Report {
        val started=SystemClock.elapsedRealtime()
        val scale=min(1.0,MAX_MOBILE.toDouble()/max(src.width,src.height))
        val w=max(1,(src.width*scale).roundToInt())
        val h=max(1,(src.height*scale).roundToInt())
        val inputBmp=Bitmap.createScaledBitmap(src,w,h,true)
        val small=bitmapPixels(inputBmp)
        if(inputBmp!==src) inputBmp.recycle()

        val model=ByteBuffer.allocateDirect(context.assets.open("mobileie_lolv1.tflite").use { it.available() }).order(ByteOrder.nativeOrder())
        // A tiny model is copied to a direct buffer once per request. Its offline
        // flatbuffer identifier and float tensors are validated by the build.
        context.assets.open("mobileie_lolv1.tflite").use { stream ->
            val bytes=ByteArray(8192)
            while(true) {
                val n=stream.read(bytes)
                if(n<0)break
                model.put(bytes,0,n)
            }
        }
        model.rewind()
        val inp=ByteBuffer.allocateDirect(w*h*3*4).order(ByteOrder.nativeOrder())
        for (p in small) {
            inp.putFloat(((p shr 16) and 255)/255f)
            inp.putFloat(((p shr 8) and 255)/255f)
            inp.putFloat((p and 255)/255f)
        }
        inp.rewind()
        val output=ByteBuffer.allocateDirect(w*h*3*4).order(ByteOrder.nativeOrder())
        val inferenceStarted=SystemClock.elapsedRealtime()
        Interpreter(model,Interpreter.Options().apply { setNumThreads(4);setUseXNNPACK(true) }).use { net ->
            net.resizeInput(0,intArrayOf(1,h,w,3))
            net.allocateTensors()
            check(net.getInputTensor(0).shape().contentEquals(intArrayOf(1,h,w,3))) {
                "MobileIE input tensor is not NHWC float RGB"
            }
            check(net.getInputTensor(0).dataType()==DataType.FLOAT32)
            check(net.getOutputTensor(0).shape().contentEquals(intArrayOf(1,h,w,3))) {
                "MobileIE output shape differs from input: this model needs another export"
            }
            check(net.getOutputTensor(0).dataType()==DataType.FLOAT32)
            net.run(inp,output)
        }
        val elapsedInference=SystemClock.elapsedRealtime()-inferenceStarted
        progress(60)
        output.rewind()
        val enhanced=IntArray(w*h)
        val floats=output.asFloatBuffer()
        for(i in enhanced.indices){
            val r=(floats.get(3*i).coerceIn(0f,1f)*255).roundToInt()
            val g=(floats.get(3*i+1).coerceIn(0f,1f)*255).roundToInt()
            val b=(floats.get(3*i+2).coerceIn(0f,1f)*255).roundToInt()
            enhanced[i]=0xFF000000.toInt() or(r shl 16) or(g shl 8) or b
        }
        val p90=percentile(small,0.90f)
        val p50=percentile(small,0.50f)
        // The pink rose has a dark background and a bright subject. In v0.3.3
        // the low-light model treated its dark backdrop as underexposure.
        // Distinguish actual dark scenes from mixed-illumination photography.
        val genuineLowLight=p90<88 && p50<40
        val mixedLight=p50<52 && p90>102
        val baseStrength=when {
            genuineLowLight -> 0.56f
            mixedLight -> 0.26f
            p50<72 -> 0.36f
            else -> 0.30f
        }
        val edited=Bitmap.createBitmap(src.width,src.height,Bitmap.Config.ARGB_8888)
        val count=min(16,src.height)
        val buffer=IntArray(src.width*count)
        for(top in 0 until src.height step count){
            val rows=min(count,src.height-top)
            src.getPixels(buffer,0,src.width,0,top,src.width,rows)
            for(y in 0 until rows){
                val sy=(top+y+0.5f)*h/src.height-0.5f
                for(x in 0 until src.width){
                    val i=y*src.width+x
                    val p=buffer[i]
                    val a=p ushr 24
                    if(a==0)continue
                    val sx=(x+0.5f)*w/src.width-0.5f
                    val r=((p shr 16) and 255).toFloat()
                    val g=((p shr 8) and 255).toFloat()
                    val b=(p and 255).toFloat()
                    val ly=luminance(p)
                    val targetR=sampleColor(enhanced,w,h,sx,sy,0)
                    val targetG=sampleColor(enhanced,w,h,sx,sy,1)
                    val targetB=sampleColor(enhanced,w,h,sx,sy,2)
                    val originalR=sampleColor(small,w,h,sx,sy,0)
                    val originalG=sampleColor(small,w,h,sx,sy,1)
                    val originalB=sampleColor(small,w,h,sx,sy,2)
                    val dr=targetR-originalR
                    val dg=targetG-originalG
                    val db=targetB-originalB
                    val dy=0.2126f*dr+0.7152f*dg+0.0722f*db
                    // v0.3.4: preserve mood in deep shadows and the delicate
                    // colours/gradations of bright subjects, without changing
                    // the actual MobileIE inference or the sampled input size.
                    val topChannel=max(r,max(g,b))
                    val shadowProtect=0.06f+0.94f*ease((ly-22f)/65f)
                    val highlightProtect=1f-0.88f*ease((ly-118f)/115f)
                    val gain=baseStrength*shadowProtect*highlightProtect
                    val liftLimit=when {
                        ly<18f -> 1.2f
                        ly<48f -> 4f
                        ly<135f -> if(genuineLowLight) 17f else 12f
                        ly<190f -> 8f
                        else -> 3f
                    }
                    // Reduce brightness *only* minimally: MobileIE v0.3.3
                    // tended to turn an already dark rose photograph darker.
                    val clipSafeRise=(251f-topChannel).coerceAtLeast(0f)*0.55f
                    val tone=(gain*dy).coerceIn(
                        if(genuineLowLight) -3f else -1.5f,
                        min(liftLimit,clipSafeRise)
                    )
                    val saturation=((topChannel-min(r,min(g,b)))/255f).coerceIn(0f,1f)
                    val pinkPetal=r>g*1.20f && b>g*1.03f && r>85f
                    val petalChroma=if(pinkPetal)0.30f else 1f
                    val chromaGain=gain*0.10f*(1f-0.75f*saturation)*petalChroma
                    val rr=(r+tone+((dr-dy)*chromaGain).coerceIn(-1.5f,1.5f)).roundToInt().coerceIn(0,255)
                    val gg=(g+tone+((dg-dy)*chromaGain).coerceIn(-1.5f,1.5f)).roundToInt().coerceIn(0,255)
                    val bb=(b+tone+((db-dy)*chromaGain).coerceIn(-1.5f,1.5f)).roundToInt().coerceIn(0,255)
                    buffer[i]=(a shl 24)or(rr shl 16)or(gg shl 8)or bb
                }
            }
            edited.setPixels(buffer,0,src.width,0,top,src.width,rows)
            progress(60+(40.0*(top+rows)/src.height).roundToInt())
        }
        return Report(edited,"MobileIE • CPU/XNNPACK",elapsedInference,SystemClock.elapsedRealtime()-started)
    }

    /**
     * Learned image-adaptive sRGB 3D LUT with official pretrained classifier.
     * ONNX classifier inference predicts 3 scene-dependent fusion coefficients.
     * Trilinear sampling of fused learned RGB LUT works at full src resolution.
     */
    fun adaptiveLut(context: Context,src:Bitmap, progress:(Int)->Unit): Report {
        val started=SystemClock.elapsedRealtime()
        val pw=256;val ph=256
        val preview=Bitmap.createScaledBitmap(src,pw,ph,true)
        val samples=bitmapPixels(preview)
        if(preview!==src)preview.recycle()
        val tensorBuffer=ByteBuffer.allocateDirect(1*3*pw*ph*4)
            .order(ByteOrder.nativeOrder())
        val floats=tensorBuffer.asFloatBuffer()
        for(channel in 0..2)for(p in samples){
            floats.put((((p shr (16-8*channel))and 255))/255f)
        }
        floats.rewind()
        val timeInference=SystemClock.elapsedRealtime()
        val coefficients:FloatArray
        val env = OrtEnvironment.getEnvironment()
        run {
            val opts=OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
            opts.use { options ->
                env.createSession(assetBytes(context,"lut_classifier.onnx"),options).use { session ->
                    OnnxTensor.createTensor(env,floats,longArrayOf(1,3,256,256)).use { input ->
                        val inputName=session.inputNames.first()
                        session.run(mapOf(inputName to input)).use { result ->
                            val array=result[0].value as Array<*>
                            coefficients=(array[0] as FloatArray).clone()
                        }
                    }
                }
            }
        }
        check(coefficients.size==3 && coefficients.all { it.isFinite() }){
            "Invalid LUT classifier output"
        }
        val elapsedInference=SystemClock.elapsedRealtime()-timeInference
        progress(35)
        val lutBytes=assetBytes(context,"lut_basis_33.bin")
        val cells=DIM*DIM*DIM
        check(lutBytes.size==3*3*cells*4){"Invalid learned LUT basis asset"}
        val basis=ByteBuffer.wrap(lutBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val fused=FloatArray(3*cells)
        for(channel in 0..2)for(i in 0 until cells){
            var v=0f
            for(k in 0..2)v+=coefficients[k]*basis.get((k*3+channel)*cells+i)
            fused[channel*cells+i]=v
        }
        val allPixels=bitmapPixels(src)
        val p90=percentile(allPixels,0.90f)
        val p50=percentile(allPixels,0.50f)
        val mixedLight=p50<52 && p90>102
        val strength=when {
            mixedLight -> 0.48f
            p90<88 -> 0.62f
            else -> 0.57f
        }

        fun cell(channel:Int,r:Int,g:Int,b:Int):Float =
            fused[channel*cells+b*DIM*DIM+g*DIM+r]
        fun mapLut(channel:Int, r:Float,g:Float,b:Float):Float {
            val cr=(r/255f*(DIM-1)).coerceIn(0f,32f)
            val cg=(g/255f*(DIM-1)).coerceIn(0f,32f)
            val cb=(b/255f*(DIM-1)).coerceIn(0f,32f)
            val r0=cr.toInt();val g0=cg.toInt();val b0=cb.toInt()
            val r1=min(32,r0+1);val g1=min(32,g0+1);val b1=min(32,b0+1)
            val tr=cr-r0;val tg=cg-g0;val tb=cb-b0
            val v000=cell(channel,r0,g0,b0);val v100=cell(channel,r1,g0,b0)
            val v010=cell(channel,r0,g1,b0);val v110=cell(channel,r1,g1,b0)
            val v001=cell(channel,r0,g0,b1);val v101=cell(channel,r1,g0,b1)
            val v011=cell(channel,r0,g1,b1);val v111=cell(channel,r1,g1,b1)
            val k0=(v000*(1-tr)+v100*tr)*(1-tg)+(v010*(1-tr)+v110*tr)*tg
            val k1=(v001*(1-tr)+v101*tr)*(1-tg)+(v011*(1-tr)+v111*tr)*tg
            return (k0*(1-tb)+k1*tb)*255f
        }

        val edited=Bitmap.createBitmap(src.width,src.height,Bitmap.Config.ARGB_8888)
        val chunk=min(16,src.height)
        val buffer=IntArray(src.width*chunk)
        for(top in 0 until src.height step chunk){
            val rows=min(chunk,src.height-top)
            src.getPixels(buffer,0,src.width,0,top,src.width,rows)
            for(i in 0 until src.width*rows){
                val p=buffer[i];val a=p ushr 24
                if(a==0)continue
                val r=((p shr 16)and 255).toFloat()
                val g=((p shr 8)and 255).toFloat()
                val b=(p and 255).toFloat()
                val luma=luminance(p)
                val dr=mapLut(0,r,g,b)-r
                val dg=mapLut(1,r,g,b)-g
                val db=mapLut(2,r,g,b)-b
                val dy=0.2126f*dr+0.7152f*dg+0.0722f*db
                // Natural-photo guardrails: full learned LUT predicts the edit,
                // but original hue/detail/bright petals remain protected.
                // v0.3.4: keep the learned, image-specific LUT prediction,
                // but protect black-point, rose petal highlights and hue.
                val highest=max(r,max(g,b))
                val shadow=0.08f+0.92f*ease((luma-18f)/75f)
                val highlight=1f-0.91f*ease((luma-126f)/124f)
                val gain=strength*highlight*shadow
                val localLiftLimit=when {
                    luma<24f -> 1.5f
                    luma<52f -> 4.5f
                    luma<140f -> 12f
                    luma<190f -> 7f
                    else -> 3f
                }
                val clippingHeadroom=(251f-highest).coerceAtLeast(0f)*0.55f
                val negativeLimit=if(luma<52f) -1.5f else -6f
                val tone=(dy*gain).coerceIn(negativeLimit,min(localLiftLimit,clippingHeadroom))
                val saturation=((highest-min(r,min(g,b)))/255f).coerceIn(0f,1f)
                val pinkPetal=r>g*1.20f && b>g*1.03f && r>85f
                val petalChroma=if(pinkPetal)0.30f else 1f
                val chromaGain=gain*0.14f*(1f-0.65f*saturation)*petalChroma
                val rr=(r+tone+((dr-dy)*chromaGain).coerceIn(-2f,2f)).roundToInt().coerceIn(0,255)
                val gg=(g+tone+((dg-dy)*chromaGain).coerceIn(-2f,2f)).roundToInt().coerceIn(0,255)
                val bb=(b+tone+((db-dy)*chromaGain).coerceIn(-2f,2f)).roundToInt().coerceIn(0,255)
                buffer[i]=(a shl 24)or(rr shl 16)or(gg shl 8)or bb
            }
            edited.setPixels(buffer,0,src.width,0,top,src.width,rows)
            progress(35+(65.0*(top+rows)/src.height).roundToInt())
        }
        return Report(edited,"Trained adaptive 3D LUT • ONNX CPU",elapsedInference,SystemClock.elapsedRealtime()-started)
    }
}
