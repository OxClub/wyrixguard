package com.wyrix.guard.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.wyrix.guard.core.PhotoPolicy
import com.wyrix.guard.data.remote.SupabaseApi
import com.wyrix.guard.system.Perms
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EvidenceRepository @Inject constructor(
    @ApplicationContext private val ctx: Context, private val api: SupabaseApi, private val store: SecureStore,
) {
    private val dir get() = File(ctx.filesDir, "pending_photos").apply { mkdirs() }

    fun canCapture(force: Boolean = false): Boolean = PhotoPolicy.canCapture(
        enabled = store.photoEnabled || force, cameraGranted = Perms.camera(ctx), enrolled = store.deviceId != null,
        lastAtMs = store.lastPhotoAt, nowMs = System.currentTimeMillis(), force = force)

    fun savePending(src: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, bounds)
        val opts = BitmapFactory.Options().apply { inSampleSize = PhotoPolicy.sampleSize(bounds.outWidth, bounds.outHeight) }
        val raw = BitmapFactory.decodeFile(src.path, opts)
        val degrees = runCatching { ExifInterface(src.path).rotationDegrees }.getOrDefault(0)
        if (raw != null) {
            val bmp = if (degrees != 0) Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(degrees.toFloat()) }, true) else raw
            File(dir, "${UUID.randomUUID()}.jpg").outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            store.lastPhotoAt = System.currentTimeMillis()
        }
        src.delete()
        dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(20)?.forEach { it.delete() }
    }

    suspend fun uploadPending() {
        val uid = store.userId ?: return
        val dev = store.deviceId ?: return
        for (f in dir.listFiles()?.sortedBy { it.lastModified() }.orEmpty()) {
            val path = "$uid/$dev/${f.nameWithoutExtension}.jpg"
            try {
                try { api.uploadObject("evidence", path, f.readBytes(), "image/jpeg") }
                catch (e: SupabaseApi.ApiException) { if (e.status != 409 && !e.code.contains("Duplicate", true)) throw e }
                api.rest("POST", "intruder_evidence?on_conflict=storage_path", buildJsonObject {
                    put("device_id", dev); put("storage_path", path); put("captured_at", Instant.ofEpochMilli(f.lastModified()).toString())
                }, "resolution=ignore-duplicates,return=minimal")
                f.delete()
            } catch (e: SupabaseApi.ApiException) {
                if (e.status in 400..499 && e.status !in setOf(401, 403, 408, 429)) f.delete() else throw e
            }
        }
    }
}
