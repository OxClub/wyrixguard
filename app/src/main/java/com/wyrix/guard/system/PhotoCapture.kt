package com.wyrix.guard.system

import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.wyrix.guard.di.entry
import com.wyrix.guard.work.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/** One front-camera photo, only while the given activity is on screen (never from a service/receiver/worker). */
object PhotoCapture {
    fun capture(activity: ComponentActivity, force: Boolean = false, onResult: (Boolean) -> Unit = {}) {
        val ep = activity.entry()
        if (!ep.evidence().canCapture(force)) { onResult(false); return }
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            val provider = try { future.get() } catch (_: Exception) { onResult(false); return@addListener }
            val ic = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            try { provider.unbindAll(); provider.bindToLifecycle(activity, CameraSelector.DEFAULT_FRONT_CAMERA, ic) }
            catch (_: Exception) { onResult(false); return@addListener }
            val out = File(activity.cacheDir, "cap-${UUID.randomUUID()}.jpg")
            ic.takePicture(ImageCapture.OutputFileOptions.Builder(out).build(), ContextCompat.getMainExecutor(activity),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                        provider.unbindAll()
                        CoroutineScope(Dispatchers.IO).launch {
                            ep.evidence().savePending(out)
                            ep.tracking().recordEvent("PHOTO_CAPTURED", mapOf("source" to if (force) "manual" else "lost_or_alarm_screen"))
                            Scheduler.scheduleSync(activity.applicationContext)
                        }
                        onResult(true)
                    }
                    override fun onError(e: ImageCaptureException) { provider.unbindAll(); out.delete(); onResult(false) }
                })
        }, ContextCompat.getMainExecutor(activity))
    }
}
