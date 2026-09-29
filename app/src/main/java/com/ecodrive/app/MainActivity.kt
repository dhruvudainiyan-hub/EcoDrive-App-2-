package com.ecodrive.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.OutputStream
import java.util.Locale
import java.util.UUID

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var socket: BluetoothSocket? = null
    private var out: OutputStream? = null
    private var adapter: BluetoothAdapter? = null

    private lateinit var tvLog: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvHeard: TextView
    private lateinit var scroll: ScrollView
    private lateinit var etCmd: EditText

    private val REQ_PERM = 1
    private val REQ_VOICE = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvLog = findViewById(R.id.tvLog)
        tvStatus = findViewById(R.id.tvStatus)
        tvHeard = findViewById(R.id.tvHeard)
        scroll = findViewById(R.id.scroll)
        etCmd = findViewById(R.id.etCmd)
        adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter

        findViewById<Button>(R.id.btnConnect).setOnClickListener { pickDevice() }
        findViewById<Button>(R.id.btnSend).setOnClickListener { sendFromBox() }
        findViewById<Button>(R.id.btnMic).setOnClickListener { startVoice() }
        etCmd.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) { sendFromBox(); true } else false
        }
        mapOf(
            R.id.bLeft to "Turn left by 90 degrees", R.id.bRight to "Turn right by 90 degrees",
            R.id.bFwd to "Go forward for 1 sec", R.id.bBack to "Go backward for 1 sec",
            R.id.bStop to "Stop", R.id.bRead to "Show readings"
        ).forEach { (id, cmd) -> findViewById<Button>(id).setOnClickListener { send(cmd) } }

        requestPerms()
    }

    private fun requestPerms() {
        val p = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) p.add(Manifest.permission.BLUETOOTH_CONNECT)
        val need = p.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) ActivityCompat.requestPermissions(this, need.toTypedArray(), REQ_PERM)
    }

    private fun pickDevice() {
        val ad = adapter
        if (ad == null || !ad.isEnabled) { toast("Turn on Bluetooth first"); return }
        val devices: List<BluetoothDevice> = ad.bondedDevices.toList()
        if (devices.isEmpty()) { toast("Pair HC-05 in phone Bluetooth settings first (PIN 1234 / 0000)"); return }
        val names = devices.map { "${it.name}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Select HC-05")
            .setItems(names) { _, i -> connect(devices[i]) }.show()
    }

    private fun connect(d: BluetoothDevice) {
        tvStatus.text = "Connecting…"
        Thread {
            try { socket?.close() } catch (_: Exception) {}
            adapter?.cancelDiscovery()
            var s: BluetoothSocket? = null
            val errors = StringBuilder()

            // Attempt 1: standard secure SPP
            // Attempt 2: insecure SPP
            // Attempt 3: reflection fallback on RFCOMM channel 1 (fixes most HC-05 "read ret: -1" errors)
            for (attempt in 1..3) {
                try {
                    val t: BluetoothSocket = when (attempt) {
                        1 -> d.createRfcommSocketToServiceRecord(SPP)
                        2 -> d.createInsecureRfcommSocketToServiceRecord(SPP)
                        else -> d.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                            .invoke(d, 1) as BluetoothSocket
                    }
                    t.connect()
                    s = t
                    break
                } catch (e: Exception) {
                    errors.append("attempt $attempt: ${e.message}\n")
                    try { Thread.sleep(500) } catch (_: Exception) {}
                }
            }

            if (s == null) {
                runOnUiThread { tvStatus.text = "Disconnected"; log("[connection failed]\n$errors") }
                return@Thread
            }
            socket = s
            out = s.outputStream
            runOnUiThread { tvStatus.text = "Connected: ${d.name}"; log("[connected]") }
            readLoop(s)
        }.start()
    }

    private fun readLoop(s: BluetoothSocket) {
        val buf = ByteArray(512)
        val sb = StringBuilder()
        try {
            val input = s.inputStream
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                sb.append(String(buf, 0, n))
                while (true) {
                    val idx = sb.indexOf("\n")
                    if (idx < 0) break
                    val line = sb.substring(0, idx).trim()
                    sb.delete(0, idx + 1)
                    if (line.isNotEmpty()) runOnUiThread { log(line) }
                }
            }
        } catch (_: Exception) { }
        runOnUiThread { tvStatus.text = "Disconnected"; log("[disconnected]") }
    }

    private fun send(cmd: String) {
        val o = out
        if (o == null) { toast("Not connected"); return }
        log("> $cmd")
        Thread {
            try { o.write((cmd + "\n").toByteArray()); o.flush() }
            catch (e: Exception) { runOnUiThread { log("[send failed: ${e.message}]") } }
        }.start()
    }

    private fun sendFromBox() {
        val t = etCmd.text.toString().trim()
        if (t.isNotEmpty()) { send(t); etCmd.setText("") }
    }

    private fun startVoice() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPerms(); return
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a command, e.g. \"go forward for 2 seconds\"")
        }
        try { startActivityForResult(i, REQ_VOICE) }
        catch (e: Exception) { toast("Speech recognition not available on this phone") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VOICE && resultCode == RESULT_OK) {
            val heard = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
            tvHeard.text = "Heard: $heard"
            send(wordsToDigits(heard))
        }
    }

    // Converts spoken numbers ("turn right ninety degrees") into digits for the Arduino parser
    private fun wordsToDigits(s: String): String {
        var t = " " + s.lowercase() + " "
        val map = listOf(
            "one eighty" to "180", "hundred eighty" to "180", "two seventy" to "270",
            "three sixty" to "360", "forty five" to "45", "ninety" to "90", "sixty" to "60",
            "thirty" to "30", "ten" to "10", "nine" to "9", "eight" to "8", "seven" to "7",
            "six" to "6", "five" to "5", "four" to "4", "three" to "3", "two" to "2", "one" to "1"
        )
        for ((w, d) in map) t = t.replace(" $w ", " $d ")
        return t.trim()
    }

    private fun log(s: String) {
        tvLog.append(s + "\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onDestroy() { super.onDestroy(); try { socket?.close() } catch (_: Exception) {} }
}
