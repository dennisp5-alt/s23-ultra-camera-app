package com.dennisp.s23lab.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.dennisp.s23lab.core.JsonOut
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Instant
import org.json.JSONObject

/**
 * The single private model file plus a manifest that records exactly which model is installed.
 * Import is copy → hash → fsync → atomic move, so a failed or interrupted import never removes the
 * previous model.
 */
class ModelStore(context: Context) {
    private val app = context.applicationContext
    val modelFile = File(app.filesDir, "model.litertlm")
    private val manifestFile = File(app.filesDir, "model-manifest.json")

    data class Manifest(
        val displayName: String,
        val sizeBytes: Long,
        val sha256: String,
        val importedUtc: String,
        val runtime: String,
        val checkPassed: Boolean? = null,
        val checkText: String? = null,
        val checkedUtc: String? = null,
        val thinkingSeen: Boolean? = null,
    ) {
        val shortHash: String get() = sha256.take(12)
        fun toJson(): String = JsonOut.write(linkedMapOf(
            "display_name" to displayName, "size_bytes" to sizeBytes, "sha256" to sha256, "imported_utc" to importedUtc,
            "runtime" to runtime, "check_passed" to checkPassed, "check_text" to checkText, "checked_utc" to checkedUtc,
            "thinking_seen" to thinkingSeen,
        ))
    }

    fun hasModel(): Boolean = modelFile.isFile && modelFile.length() > MIN_BYTES

    fun manifest(): Manifest? = runCatching {
        if (!manifestFile.isFile) return null
        val j = JSONObject(manifestFile.readText())
        Manifest(
            displayName = j.optString("display_name", "unknown"),
            sizeBytes = j.optLong("size_bytes"),
            sha256 = j.optString("sha256"),
            importedUtc = j.optString("imported_utc"),
            runtime = j.optString("runtime"),
            checkPassed = if (j.isNull("check_passed")) null else j.optBoolean("check_passed"),
            checkText = if (j.isNull("check_text")) null else j.optString("check_text"),
            checkedUtc = if (j.isNull("checked_utc")) null else j.optString("checked_utc"),
            thinkingSeen = if (j.isNull("thinking_seen")) null else j.optBoolean("thinking_seen"),
        )
    }.getOrNull()

    /** Copies the chosen file in. Blocking; call off the main thread. [onProgress] gets bytes copied. */
    fun import(uri: Uri, onProgress: (Long) -> Unit = {}): Manifest {
        val name = displayName(uri)
        val temp = File(app.filesDir, "model.litertlm.importing")
        temp.delete()
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            requireNotNull(app.contentResolver.openInputStream(uri)) { "Could not open the chosen file." }.use { raw ->
                DigestInputStream(raw, digest).use { input ->
                    FileOutputStream(temp).use { out ->
                        val buf = ByteArray(1 shl 17)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            copied += n
                            if (copied % (32L shl 20) < n) onProgress(copied)
                        }
                        out.fd.sync()
                    }
                }
            }
            require(copied > MIN_BYTES) { "File is only $copied bytes; not a model." }
            val manifest = Manifest(
                displayName = name, sizeBytes = copied,
                sha256 = digest.digest().joinToString("") { "%02x".format(it) },
                importedUtc = Instant.now().toString(), runtime = RUNTIME,
            )
            Files.move(temp.toPath(), modelFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            writeManifest(manifest)
            return manifest
        } finally {
            temp.delete()
        }
    }

    /** Records the latest model-check result in the manifest. */
    fun recordCheck(passed: Boolean, text: String, thinkingSeen: Boolean) {
        val m = manifest() ?: return
        writeManifest(m.copy(checkPassed = passed, checkText = text, checkedUtc = Instant.now().toString(), thinkingSeen = thinkingSeen))
    }

    private fun writeManifest(m: Manifest) {
        val tmp = File(app.filesDir, "model-manifest.json.tmp")
        FileOutputStream(tmp).use { it.write(m.toJson().toByteArray()); it.fd.sync() }
        Files.move(tmp.toPath(), manifestFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun displayName(uri: Uri): String =
        runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "unknown"

    companion object {
        const val MIN_BYTES = 2_000_000L
        const val RUNTIME = "litertlm-android 0.19.0"
    }
}
