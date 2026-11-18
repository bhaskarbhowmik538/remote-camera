package com.bhaskar.remotecam

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.*
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class CamService : Service() {
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val ui = Handler(Looper.getMainLooper())
    private val camThread = HandlerThread("cam").also { it.start() }
    private val ch = Handler(camThread.looper)
    private var ws: WebSocket? = null
    private var server = ""
    private var code = ""
    @Volatile private var active = true
    @Volatile private var viewing = false
    @Volatile private var isRec = false
    private var lastFrame = 0L
    private var sensorRot = 90

    private var dev: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var mr: MediaRecorder? = null
    private var recUri: Uri? = null
    private var recPfd: ParcelFileDescriptor? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        server = i?.getStringExtra("server") ?: ""
        code = i?.getStringExtra("code") ?: ""
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("rc", "Remote camera", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, 1, notif("Camera is controllable by a remote phone"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        openCamera()
        connect()
        return START_NOT_STICKY
    }

    private fun notif(t: String): Notification = NotificationCompat.Builder(this, "rc")
        .setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("Remote Camera").setContentText(t).setOngoing(true).build()

    private fun closeCamera() {
        try { session?.close() } catch (e: Exception) {}
        session = null
        try { dev?.close() } catch (e: Exception) {}
        dev = null
        try { reader?.close() } catch (e: Exception) {}
        reader = null
    }

    private fun reopen(rec: Boolean) {
        closeCamera()
        ch.postDelayed({
            try { openCamera(rec) } catch (e: Exception) { if (rec) ui.post { cleanupRecorder(false); status() } }
        }, 400)
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(rec: Boolean = false) {
        val cm = getSystemService(CameraManager::class.java)
        val camId = cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
        val chars = cm.getCameraCharacteristics(camId)
        sensorRot = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.getOutputSizes(ImageFormat.YUV_420_888)
        val sz = sizes.filter { it.width * 9 == it.height * 16 && it.width <= 960 }.maxByOrNull { it.width }
            ?: sizes.filter { it.width <= 960 }.maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height }!!
        reader = ImageReader.newInstance(sz.width, sz.height, ImageFormat.YUV_420_888, 2).also { it.setOnImageAvailableListener({ r -> frame(r) }, ch) }
        cm.openCamera(camId, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) { dev = d; startSession(rec) }
            override fun onDisconnected(d: CameraDevice) { d.close(); dev = null }
            override fun onError(d: CameraDevice, e: Int) { d.close(); dev = null; if (mr != null && !isRec) ui.post { cleanupRecorder(false); status() } }
        }, ch)
    }

    private fun startSession(rec: Boolean) {
        val d = dev ?: return
        try { session?.close() } catch (e: Exception) {}
        session = null
        val surfaces = mutableListOf<Surface>(reader!!.surface)
        if (rec) surfaces.add(mr!!.surface)
        d.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                try {
                    val rb = d.createCaptureRequest(if (rec) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
                    surfaces.forEach { rb.addTarget(it) }
                    rb.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    s.setRepeatingRequest(rb.build(), null, ch)
                    if (rec) { mr?.start(); isRec = true; ui.post { status() } }
                } catch (e: Exception) {
                    if (rec) { cleanupRecorder(false); ui.post { status() }; startSession(false) }
                }
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                if (rec) { cleanupRecorder(false); ui.post { status() }; startSession(false) }
            }
        }, ch)
    }

    private var btOn = false
    private var starting = false

    private fun btHeadset(): Boolean = try {
        getSystemService(BluetoothManager::class.java).adapter.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED
    } catch (e: Exception) { false }

    private fun waitSco(go: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 31) { ui.postDelayed({ go() }, 1500); return }
        var done = false
        var rcv: BroadcastReceiver? = null
        fun fin() {
            if (done) return
            done = true
            try { unregisterReceiver(rcv) } catch (e: Exception) {}
            ui.postDelayed({ go() }, 400)
        }
        rcv = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) == AudioManager.SCO_AUDIO_STATE_CONNECTED) fin()
            }
        }
        registerReceiver(rcv, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
        ui.postDelayed({ fin() }, 4000)
    }

    private fun routeBt(): Boolean {
        if (!btHeadset()) return false
        val am = getSystemService(AudioManager::class.java)
        return try {
            if (Build.VERSION.SDK_INT >= 31) {
                val d = am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                if (d == null) false else { am.mode = AudioManager.MODE_IN_COMMUNICATION; am.setCommunicationDevice(d) }
            } else {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
                true
            }
        } catch (e: Exception) { false }
    }

    private fun unrouteBt() {
        val am = getSystemService(AudioManager::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
            else { am.isBluetoothScoOn = false; am.stopBluetoothSco() }
            am.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {}
    }

    private fun startRec() {
        if (isRec || mr != null || dev == null || starting) return
        starting = true
        btOn = routeBt()
        if (btOn) waitSco { doStartRec() } else doStartRec()
    }

    private fun doStartRec() {
        starting = false
        if (!active) return
        try {
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "RemoteCam_" + System.currentTimeMillis())
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/RemoteCam")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: return
            recUri = uri
            val pfd = contentResolver.openFileDescriptor(uri, "w") ?: return
            recPfd = pfd
            val m = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            m.setAudioSource(if (btOn) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC)
            m.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            m.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            m.setOutputFile(pfd.fileDescriptor)
            m.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            m.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            m.setVideoSize(1280, 720)
            m.setVideoFrameRate(30)
            m.setVideoEncodingBitRate(6_000_000)
            m.setAudioEncodingBitRate(128_000)
            m.setAudioSamplingRate(44100)
            m.setOrientationHint(sensorRot)
            m.prepare()
            mr = m
            reopen(true)
        } catch (e: Exception) {
            cleanupRecorder(false); status()
        }
    }

    private fun stopRec() {
        if (!isRec) return
        isRec = false
        try { session?.stopRepeating(); session?.abortCaptures() } catch (e: Exception) {}
        try { mr?.stop() } catch (e: Exception) {}
        cleanupRecorder(true)
        reopen(false)
        status()
    }

    private fun cleanupRecorder(ok: Boolean) {
        if (btOn) { unrouteBt(); btOn = false }
        try { mr?.release() } catch (e: Exception) {}
        mr = null
        try { recPfd?.close() } catch (e: Exception) {}
        recPfd = null
        recUri?.let { u ->
            try {
                if (ok) contentResolver.update(u, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                else contentResolver.delete(u, null, null)
            } catch (e: Exception) {}
        }
        recUri = null
    }

    private fun nv21(img: Image): ByteArray {
        val w = img.width; val h = img.height
        val out = ByteArray(w * h * 3 / 2)
        val y = img.planes[0]; var pos = 0
        for (r in 0 until h) { y.buffer.position(r * y.rowStride); y.buffer.get(out, pos, w); pos += w }
        val u = img.planes[1]; val v = img.planes[2]
        for (r in 0 until h / 2) for (c in 0 until w / 2) {
            out[pos++] = v.buffer.get(r * v.rowStride + c * v.pixelStride)
            out[pos++] = u.buffer.get(r * u.rowStride + c * u.pixelStride)
        }
        return out
    }

    private fun frame(r: ImageReader) {
        val img = r.acquireLatestImage() ?: return
        try {
            val now = System.currentTimeMillis()
            if (viewing && now - lastFrame > 160) {
                lastFrame = now
                val w = img.width; val h = img.height
                val o = ByteArrayOutputStream()
                YuvImage(nv21(img), ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 50, o)
                val b = o.toByteArray()
                val bmp = BitmapFactory.decodeByteArray(b, 0, b.size)
                val rot = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(sensorRot.toFloat()) }, true)
                val o2 = ByteArrayOutputStream()
                rot.compress(Bitmap.CompressFormat.JPEG, 50, o2)
                ws?.send(ByteString.of(*o2.toByteArray()))
            }
        } catch (e: Exception) {
        } finally { img.close() }
    }

    private fun connect() {
        if (!active) return
        ws = http.newWebSocket(Request.Builder().url("$server/$code").build(), object : WebSocketListener() {
            override fun onOpen(w: WebSocket, r: Response) { status() }
            override fun onMessage(w: WebSocket, t: String) {
                val j = try { JSONObject(t) } catch (e: Exception) { return }
                val a = j.optString("a")
                val on = j.optBoolean("on")
                ui.post { when (a) { "start" -> startRec(); "stop" -> stopRec(); "hello" -> status(); "view" -> viewing = on } }
            }
            override fun onFailure(w: WebSocket, e: Throwable, r: Response?) { retry() }
            override fun onClosed(w: WebSocket, c: Int, r: String) { retry() }
        })
    }
    private fun retry() { if (active) ui.postDelayed({ connect() }, 3000) }

    private fun status() {
        ws?.send(JSONObject().put("t", "status").put("s", if (isRec) "recording" else "idle").toString())
        getSystemService(NotificationManager::class.java).notify(1, notif(if (isRec) "Recording" else "Camera is controllable by a remote phone"))
    }

    override fun onDestroy() {
        active = false
        viewing = false
        if (isRec) {
            isRec = false
            try { session?.stopRepeating(); session?.abortCaptures() } catch (e: Exception) {}
            try { mr?.stop() } catch (e: Exception) {}
            cleanupRecorder(true)
        }
        if (btOn) { unrouteBt(); btOn = false }
        try { session?.close() } catch (e: Exception) {}
        try { dev?.close() } catch (e: Exception) {}
        try { reader?.close() } catch (e: Exception) {}
        ws?.close(1000, null)
        camThread.quitSafely()
        super.onDestroy()
    }
}
