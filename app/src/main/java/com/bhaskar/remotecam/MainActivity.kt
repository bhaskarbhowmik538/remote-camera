package com.bhaskar.remotecam

import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import okio.ByteString
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private val http = OkHttpClient()
    private val ui = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var serverField: EditText? = null
    private val prefs by lazy { getSharedPreferences("rc", MODE_PRIVATE) }

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.CAMERA] == true && r[Manifest.permission.RECORD_AUDIO] == true) startCamera()
        else toast("Camera and microphone permission are required")
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val sv = ScrollView(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        sv.addView(root); setContentView(sv)
        home()
    }

    override fun onDestroy() { ws?.close(1000, null); super.onDestroy() }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
    private fun label(t: String, sp: Float = 16f, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; setPadding(0, 16, 0, 16); if (bold) setTypeface(typeface, Typeface.BOLD)
    }.also { root.addView(it) }
    private fun button(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }.also { root.addView(it) }
    private fun field(hint: String, v: String = "") = EditText(this).apply { setText(v); this.hint = hint }.also { root.addView(it) }

    private fun server(): String {
        var s = (serverField?.text?.toString() ?: prefs.getString("server", "") ?: "").trim().trimEnd('/')
        s = s.replace("https://", "wss://").replace("http://", "ws://")
        if (s.isNotEmpty() && !s.startsWith("ws")) s = "wss://$s"
        prefs.edit().putString("server", s).apply()
        return s
    }
    private fun serverOk(): Boolean { val ok = server().isNotEmpty(); if (!ok) toast("Enter the server address first"); return ok }
    private fun cmd(a: String) = JSONObject().put("t", "cmd").put("a", a).toString()
    private fun view(on: Boolean) = JSONObject().put("t", "cmd").put("a", "view").put("on", on).toString()

    private fun home() {
        ws?.close(1000, null); ws = null
        root.removeAllViews()
        label("Remote Camera", 26f, true)
        label("Start and stop recording on another phone's camera.")
        serverField = field("Server address", prefs.getString("server", "") ?: "")
        button("This phone is the camera") {
            if (serverOk()) perms.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.BLUETOOTH_CONNECT))
        }
        button("This phone is the remote") { if (serverOk()) remote() }
        label("Built by Bhaskar Bhowmik", 13f)
    }

    private fun startCamera() {
        val code = (100000..999999).random().toString()
        val i = Intent(this, CamService::class.java).putExtra("server", server()).putExtra("code", code)
        startForegroundService(i)
        root.removeAllViews()
        label("Enter this code on the remote phone")
        label(code, 44f, true)
        label("While this is on, any remote with this code can start and stop recording on this phone. A notification stays visible the whole time. Videos are saved on this phone in Movies/RemoteCam.", 14f)
        button("Stop sharing") { stopService(Intent(this, CamService::class.java)); home() }
    }

    private fun remote() {
        root.removeAllViews()
        label("Enter the 6-digit code shown on the camera phone")
        val code = field("000000").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        button("Connect") {
            val c = code.text.toString().trim()
            if (c.length == 6) panel(c) else toast("Enter all 6 digits")
        }
        button("Back") { home() }
    }

    private fun panel(code: String) {
        root.removeAllViews()
        val st = label("Connecting…", 18f, true)
        val img = ImageView(this).apply { adjustViewBounds = true; setBackgroundColor(Color.BLACK); minimumHeight = 500 }
        root.addView(img)
        var got = false
        var watching = true
        ws = http.newWebSocket(Request.Builder().url("${server()}/$code").build(), object : WebSocketListener() {
            override fun onOpen(w: WebSocket, r: Response) { w.send(cmd("hello")); w.send(view(true)) }
            override fun onMessage(w: WebSocket, t: String) {
                got = true
                if (watching) w.send(view(true))
                val s = JSONObject(t).optString("s")
                ui.post { st.text = when (s) { "recording" -> "Camera is recording"; "idle" -> "Camera ready"; else -> s } }
            }
            override fun onMessage(w: WebSocket, bytes: ByteString) {
                got = true
                val bm = BitmapFactory.decodeByteArray(bytes.toByteArray(), 0, bytes.size)
                if (bm != null) ui.post { img.setImageBitmap(bm) }
            }
            override fun onFailure(w: WebSocket, e: Throwable, r: Response?) { ui.post { st.text = "Connection lost. Check the server address." } }
        })
        button("Start recording") { ws?.send(cmd("start")) }
        button("Stop recording") { ws?.send(cmd("stop")) }
        var tgl: Button? = null
        tgl = button("Pause live view") {
            watching = !watching
            ws?.send(view(watching))
            tgl?.text = if (watching) "Pause live view" else "Resume live view"
        }
        button("Disconnect") { ws?.send(view(false)); home() }
        ui.postDelayed({ if (!got) st.text = "No camera found with that code." }, 6000)
    }
}
