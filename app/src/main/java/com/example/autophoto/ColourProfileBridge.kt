package com.example.autophoto

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Experimental P3 JPEG export. The approved Polished renderer ALWAYS operates
 * on exactly the same sRGB pixels as before. For source-P3 pixels outside
 * sRGB, restore only the original out-of-gamut CHROMA after proper sRGB->P3
 * conversion. Preserve the Polished luminance; never just retag sRGB numbers.
 */
internal object ColourProfileBridge {
    enum class InputProfile(val label: String) {
        SRGB("sRGB"), P3("Display P3"), OTHER("Other RGB → sRGB")
    }
    data class EncodedJpeg(val bytes: ByteArray)
    private val srgb=ColorSpace.get(ColorSpace.Named.SRGB)
    private val p3=ColorSpace.get(ColorSpace.Named.DISPLAY_P3)

    fun detect(space: ColorSpace?): InputProfile {
        if (space == null || space.model != ColorSpace.Model.RGB) return InputProfile.SRGB
        if (space == srgb || space.name.equals("sRGB",true)) return InputProfile.SRGB
        val rgb=space as? ColorSpace.Rgb ?: return InputProfile.OTHER
        if (!space.isWideGamut) return InputProfile.SRGB
        val a=rgb.primaries; val b=(p3 as ColorSpace.Rgb).primaries
        return if (a.size==b.size && a.indices.all { abs(a[it]-b[it])<=.018f })
            InputProfile.P3 else InputProfile.OTHER
    }

    private fun hasIcc(jpeg: ByteArray): Boolean {
        val marker="ICC_PROFILE\u0000".toByteArray(Charsets.US_ASCII)
        outer@ for(i in 0..(jpeg.size-marker.size)) {
            for(j in marker.indices) if(jpeg[i+j]!=marker[j]) continue@outer
            return true
        }
        return false
    }

    fun isVerifiedP3(jpeg: ByteArray): Boolean {
        if (jpeg.size<128 || !hasIcc(jpeg)) return false
        val opt=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(jpeg,0,jpeg.size,opt)
        return opt.outWidth>0 && opt.outHeight>0 &&
            detect(opt.outColorSpace)==InputProfile.P3
    }

    fun isVerifiedP3(resolver:ContentResolver,uri:Uri):Boolean {
        val opt=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        resolver.openFileDescriptor(uri,"r")?.use { fd ->
            BitmapFactory.decodeFileDescriptor(fd.fileDescriptor,null,opt)
        } ?: return false
        return opt.outWidth>0 && opt.outHeight>0 &&
            detect(opt.outColorSpace)==InputProfile.P3
    }

    // getPixel/setPixel use *sRGB* integer colours even for P3 bitmaps.
    // Therefore raw native bytes are used ONLY after checking the layout
    // against multiple P3-native bitmap.getColor() samples on this device.
    private fun verifiedRgba(bitmap:Bitmap,raw:ByteBuffer):Boolean {
        if(bitmap.config!=Bitmap.Config.ARGB_8888) return false
        val n=bitmap.width*bitmap.height
        for(k in 0..20) {
            val i=(k.toLong()*(n-1)/20).toInt()
            val c=bitmap.getColor(i%bitmap.width,i/bitmap.width)
            val off=i*4
            if(abs((raw.get(off).toInt() and 255)/255f-c.red())>.012f ||
               abs((raw.get(off+1).toInt() and 255)/255f-c.green())>.012f ||
               abs((raw.get(off+2).toInt() and 255)/255f-c.blue())>.012f ||
               abs((raw.get(off+3).toInt() and 255)/255f-c.alpha())>.012f)
                return false
        }
        return true
    }

    private fun byte(v:Float):Byte =
        (v.coerceIn(0f,1f)*255f).roundToInt().coerceIn(0,255).toByte()

    fun encodeP3(resolver:ContentResolver,source:Uri,
                 originalSrgb:Bitmap, polishedSrgb:Bitmap):EncodedJpeg? {
        val w=polishedSrgb.width; val h=polishedSrgb.height
        if(w!=originalSrgb.width || h!=originalSrgb.height ||
           w.toLong()*h>12_600_000L) return null
        val native=ImageDecoder.decodeBitmap(
            ImageDecoder.createSource(resolver,source)
        ) { decoder,info,_ ->
            // Keep the original orientation and pixels for native 12MP
            // photos. Re-sizing to the oriented output dimensions here
            // can accidentally swap EXIF-rotated width and height.
            require(info.size.width.toLong()*info.size.height <= 12_600_000L &&
                    max(info.size.width,info.size.height) <= 4096) {
                "Source too large for memory-safe P3 restoration"
            }
            decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM)
            decoder.setTargetColorSpace(p3)
        }
        try {
            if(native.width!=w || native.height!=h ||
               detect(native.colorSpace)!=InputProfile.P3) return null
            val count=w*h
            val nativeBytes=ByteBuffer.allocateDirect(count*4)
            native.copyPixelsToBuffer(nativeBytes)
            nativeBytes.rewind()
            if(!verifiedRgba(native,nativeBytes)) return null

            val result=ByteBuffer.allocateDirect(count*4)
            val toP3=ColorSpace.connect(srgb,p3)
            val inRow=IntArray(w);val outRow=IntArray(w)
            val before=FloatArray(3);val after=FloatArray(3)
            val delta=FloatArray(3)
            for(y in 0 until h) {
                originalSrgb.getPixels(inRow,0,w,0,y,w,1)
                polishedSrgb.getPixels(outRow,0,w,0,y,w,1)
                for(x in 0 until w) {
                    val off=(y*w+x)*4
                    val a=inRow[x];val b=outRow[x]
                    before[0]=((a shr 16) and 255)/255f
                    before[1]=((a shr 8) and 255)/255f
                    before[2]=(a and 255)/255f
                    toP3.transform(before)
                    after[0]=((b shr 16) and 255)/255f
                    after[1]=((b shr 8) and 255)/255f
                    after[2]=(b and 255)/255f
                    toP3.transform(after)
                    delta[0]=(nativeBytes.get(off).toInt() and 255)/255f-before[0]
                    delta[1]=(nativeBytes.get(off+1).toInt() and 255)/255f-before[1]
                    delta[2]=(nativeBytes.get(off+2).toInt() and 255)/255f-before[2]
                    // Preserve exact approved luminance. The input residual
                    // only contributes native source chroma outside sRGB.
                    val dy=.22897f*delta[0]+.69174f*delta[1]+.07929f*delta[2]
                    for(c in 0..2) delta[c]-=dy
                    if(max(abs(delta[0]),max(abs(delta[1]),abs(delta[2])))>.009f) {
                        var fit=1f
                        for(c in 0..2) {
                            if(delta[c]>.0001f)
                                fit=min(fit,(1f-after[c])/delta[c])
                            else if(delta[c]<-.0001f)
                                fit=min(fit,-after[c]/delta[c])
                        }
                        for(c in 0..2) after[c]+=delta[c]*fit.coerceIn(0f,1f)
                    }
                    result.put(byte(after[0]))
                    result.put(byte(after[1]))
                    result.put(byte(after[2]))
                    result.put(((b ushr 24) and 255).toByte())
                }
            }
            result.flip()
            val output=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888,true,p3)
            try {
                output.copyPixelsFromBuffer(result)
                val jpeg=ByteArrayOutputStream()
                if(!output.compress(Bitmap.CompressFormat.JPEG,98,jpeg))
                    return null
                val bytes=jpeg.toByteArray()
                if(!isVerifiedP3(bytes)) return null
                return EncodedJpeg(bytes)
            } finally { output.recycle() }
        } finally { native.recycle() }
    }
}
