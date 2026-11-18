package com.bhaskar.remotecam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class CamService : LifecycleService() {
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val ui = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var provider: ProcessCameraProvider? = null
    private var video: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var server = ""
    private var code = ""
    private var active = true
    private var viewing = false
    private var lastFrame = 0L
    private val exec = Executors.newSingleThreadExecutor()

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        super.onStartCommand(i, f, id)
        server = i?.getStringExtra("server") ?: ""
        code = i?.getStringExtra("code") ?: ""
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("rc", "Remote camera", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, 1, notif("Camera is controllable by a remote phone"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        bindCamera()
        connect()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? { super.onBind(intent); return null }

    private fun notif(t: String): Notification = NotificationCompat.Builder(this, "rc")
        .setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("Remote Camera").setContentText(t).setOngoing(true).build()

    private fun bindCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            provider = f.get()
            val rec = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))).build()
            video = VideoCapture.withOutput(rec)
            provider!!.unbindAll()
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(exec) { img ->
                try {
                    val now = System.currentTimeMillis()
                    if (viewing && now - lastFrame > 160) {
                        lastFrame = now
                        val bmp = img.toBitmap()
                        val m = Matrix().apply { postRotate(img.imageInfo.rotationDegrees.toFloat()) }
                        val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                        val out = ByteArrayOutputStream()
                        r.compress(Bitmap.CompressFormat.JPEG, 50, out)
                        ws?.send(ByteString.of(*out.toByteArray()))
                    }
                } catch (e: Exception) {
                } finally { img.close() }
            }
            provider!!.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, video!!, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun connect() {
        if (!active) return
        ws = http.newWebSocket(Request.Builder().url("$server/$code").build(), object : WebSocketListener() {
            override fun onOpen(w: WebSocket, r: Response) { status() }
            override fun onMessage(w: WebSocket, t: String) {
                val j = try { JSONObject(t) } catch (e: Exception) { return }
                val a = j.optString("a")
                val on = j.optBoolean("on")
                ui.post { when (a) { "start" -> startRec(); "stop" -> recording?.stop(); "hello" -> status(); "view" -> viewing = on } }
            }
            override fun onFailure(w: WebSocket, e: Throwable, r: Response?) { retry() }
            override fun onClosed(w: WebSocket, c: Int, r: String) { retry() }
        })
    }
    private fun retry() { if (active) ui.postDelayed({ connect() }, 3000) }

    private fun status() {
        val s = if (recording != null) "recording" else "idle"
        ws?.send(JSONObject().put("t", "status").put("s", s).toString())
        getSystemService(NotificationManager::class.java).notify(1, notif(if (recording != null) "Recording" else "Camera is controllable by a remote phone"))
    }

    private fun startRec() {
        if (recording != null) return
        val v = video ?: return
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "RemoteCam_" + System.currentTimeMillis())
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/RemoteCam")
        }
        val opts = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(cv).build()
        recording = v.output.prepareRecording(this, opts).withAudioEnabled().start(ContextCompat.getMainExecutor(this)) { e ->
            if (e is VideoRecordEvent.Finalize) { recording = null; status() }
        }
        status()
    }

    override fun onDestroy() {
        active = false
        viewing = false
        exec.shutdown()
        recording?.stop()
        ws?.close(1000, null)
        provider?.unbindAll()
        super.onDestroy()
    }
}
