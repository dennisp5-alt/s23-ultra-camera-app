package com.dennis.aichat
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureKey(private val context: Context) {
    private val alias="DennisAIChatBraveKey"
    private val prefs=context.getSharedPreferences("secrets",Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias,null) as? SecretKey)?.let { return it }
        val generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }
    fun save(value: String) {
        if(value.isBlank()) { prefs.edit().remove("key").remove("nonce").apply(); return }
        val c=Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE,key())
        prefs.edit().putString("key",Base64.encodeToString(c.doFinal(value.toByteArray(Charsets.UTF_8)),Base64.NO_WRAP))
            .putString("nonce",Base64.encodeToString(c.iv,Base64.NO_WRAP)).apply()
    }
    fun load(): String {
        val data=prefs.getString("key",null) ?: return ""
        val nonce=prefs.getString("nonce",null) ?: return ""
        return try {
            val c=Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(nonce,Base64.NO_WRAP)))
            String(c.doFinal(Base64.decode(data,Base64.NO_WRAP)),Charsets.UTF_8)
        } catch (_: Exception) { "" }
    }
}
