package com.dsh.audiohaptics

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 音频联动 · Quest 原生版
 *
 * 链路：视频音轨 -> 预分析出 0..100 强度包络（与 APK 算法一致）
 *       -> 播放时按 currentPosition 查表
 *       -> 20 Hz 按模板拼报文 -> BLE 写入
 */
class MainActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    // 分析参数（与 web/envelope.js 对齐）
    private val frameMs = 50
    private val tickMs = 50L

    private lateinit var surfaceView: SurfaceView
    private lateinit var analysisLabel: TextView
    private lateinit var levelText: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var playBtn: Button
    private lateinit var testSlider: SeekBar
    private lateinit var testLabel: TextView

    private lateinit var logView: TextView
    private lateinit var deviceBox: LinearLayout
    private lateinit var charBox: LinearLayout
    private lateinit var templateEdit: EditText
    private lateinit var maxByteEdit: EditText
    private lateinit var noRespCheck: CheckBox

    private var player: ExoPlayer? = null
    private var envelope: IntArray? = null
    private var currentLevel = 0
    private var ticking = false
    private var lastSentLevel = -1
    private var lastSentAt = 0L

    private var adapter: BluetoothAdapter? = null
    private var gatt: BluetoothGatt? = null
    private var target: BluetoothGattCharacteristic? = null
    private val seen = LinkedHashMap<String, BluetoothDevice>()
    private var scanning = false

    // ---------------- 播放 / 下发 ----------------

    private val tickRunnable = object : Runnable {
        override fun run() {
            val p = player
            val env = envelope
            if (p == null || env == null || env.isEmpty() || !p.isPlaying) {
                ticking = false
                return
            }

            var idx = (p.currentPosition / frameMs).toInt()
            if (idx < 0) idx = 0
            if (idx >= env.size) idx = env.size - 1

            val level = env[idx]
            currentLevel = level
            showLevel(level)

            val now = System.currentTimeMillis()
            if (level != lastSentLevel || now - lastSentAt > 1000L) {
                val bytes = Protocol.build(templateEdit.text.toString(), level, maxByteValue())
                if (bytes != null && write(bytes, quiet = true)) {
                    lastSentLevel = level
                    lastSentAt = now
                }
            }

            if (ticking) ui.postDelayed(this, tickMs)
        }
    }

    private fun startTick() {
        if (ticking) return
        if (envelope == null) {
            log("还没有分析好的包络：先点「选视频」")
            return
        }
        ticking = true
        ui.post(tickRunnable)
    }

    private fun ensurePlayer(): ExoPlayer {
        val existing = player
        if (existing != null) return existing

        val p = ExoPlayer.Builder(this).build()
        p.setVideoSurfaceView(surfaceView)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    ticking = false
                    playBtn.text = "播放"
                }
            }
        })
        player = p
        return p
    }

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult

        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
        }

        envelope = null
        analysisLabel.text = "分析中…"
        log("开始分析音轨：$uri")

        Thread {
            val res = AudioAnalyzer.analyze(this, uri, frameMs) { pct ->
                ui.post { analysisLabel.text = "分析中 $pct%" }
            }
            ui.post {
                if (res == null || res.envelope.isEmpty()) {
                    envelope = null
                    analysisLabel.text = "没有可用音轨"
                    log("分析失败：没解出音频（换一个文件试试）")
                } else {
                    envelope = res.envelope
                    analysisLabel.text =
                        "已分析 ${res.envelope.size} 点 · ${res.durationMs / 1000}s · ${res.sampleRate}Hz ${res.channels}ch"
                    log("分析完成：${res.envelope.size} 个强度点")

                    val p = ensurePlayer()
                    p.setMediaItem(MediaItem.fromUri(uri))
                    p.prepare()
                    playBtn.text = "播放"
                }
            }
        }.start()
    }

    // ---------------- 扫描 / GATT ----------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device ?: return
            if (seen.containsKey(dev.address)) return
            seen[dev.address] = dev
            val name = safeName(dev)
            addDeviceButton(dev, if (name.isEmpty()) "(无名)" else name, result.rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            log("扫描失败，errorCode=$errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("已连接（status=$status），发现服务…")
                ui.post { gatt.discoverServices() }
            } else {
                log("连接状态 newState=$newState status=$status")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("发现服务失败 status=$status")
                return
            }
            ui.post { showServices(gatt) }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) log("写入失败 status=$status")
        }
    }

    // ---------------- 生命周期 ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.surfaceView)
        analysisLabel = findViewById(R.id.analysisLabel)
        levelText = findViewById(R.id.levelText)
        levelBar = findViewById(R.id.levelBar)
        playBtn = findViewById(R.id.playBtn)
        testSlider = findViewById(R.id.testSlider)
        testLabel = findViewById(R.id.testLabel)
        logView = findViewById(R.id.logView)
        deviceBox = findViewById(R.id.deviceBox)
        charBox = findViewById(R.id.charBox)
        templateEdit = findViewById(R.id.templateEdit)
        maxByteEdit = findViewById(R.id.maxByteEdit)
        noRespCheck = findViewById(R.id.noRespCheck)

        templateEdit.setText("AA 03 {level} 55")

        val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        adapter = mgr?.adapter

        findViewById<Button>(R.id.pickBtn).setOnClickListener { pickVideo.launch(arrayOf("video/*")) }

        playBtn.setOnClickListener {
            val p = ensurePlayer()
            if (envelope == null) {
                log("先选一个视频，等分析完成")
                return@setOnClickListener
            }
            if (p.isPlaying) {
                p.pause()
                ticking = false
                playBtn.text = "播放"
            } else {
                p.play()
                playBtn.text = "暂停"
                startTick()
            }
        }

        findViewById<Button>(R.id.scanBtn).setOnClickListener { startScan() }
        findViewById<Button>(R.id.disconnectBtn).setOnClickListener { disconnect() }
        findViewById<Button>(R.id.clearBtn).setOnClickListener { logView.text = "" }
        findViewById<Button>(R.id.sendBtn).setOnClickListener {
            val level = testSlider.progress
            val bytes = Protocol.build(templateEdit.text.toString(), level, maxByteValue())
            if (bytes == null) log("模板解析失败") else write(bytes)
        }

        testSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                testLabel.text = "手动测试强度：$progress"
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val enabled = adapter?.let { safeEnabled(it) } == true
        log("API ${Build.VERSION.SDK_INT} · 适配器=${adapter != null} · 蓝牙=${if (enabled) "已开" else "未开"}")
        if (hasPerms()) log("权限就绪：先「选视频」，再「扫描设备」") else requestPerms()
    }

    override fun onDestroy() {
        ticking = false
        try {
            player?.release()
        } catch (e: Exception) {
        }
        player = null
        try {
            gatt?.disconnect()
        } catch (e: Exception) {
        }
        try {
            gatt?.close()
        } catch (e: Exception) {
        }
        gatt = null
        super.onDestroy()
    }

    // ---------------- 权限 ----------------

    private fun perms(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasPerms(): Boolean = perms().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPerms() {
        log("请求蓝牙权限…")
        ActivityCompat.requestPermissions(this, perms(), 1001)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 1001) return
        if (hasPerms()) {
            log("权限已授予")
        } else {
            log("权限被拒绝 —— 去「设置 → 应用 → 本应用 → 权限」打开")
        }
    }

    // ---------------- 扫描 / 连接 ----------------

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val a = adapter ?: return log("没有蓝牙适配器")
        if (!hasPerms()) return requestPerms()
        if (!safeEnabled(a)) return log("系统蓝牙是关的 —— 先在头显设置里打开蓝牙")
        val scanner = a.bluetoothLeScanner ?: return log("拿不到 BLE 扫描器")
        if (scanning) return

        seen.clear()
        deviceBox.removeAllViews()

        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(null, settings, scanCallback)
            scanning = true
            log("扫描 10 秒，请让玩具处于开机未连接状态")
        } catch (e: Exception) {
            log("扫描异常：${e.message}")
            return
        }

        ui.postDelayed({ stopScan() }, 10_000)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
        }
        log("扫描结束，${seen.size} 个设备")
    }

    private fun addDeviceButton(dev: BluetoothDevice, name: String, rssi: Int) {
        val b = Button(this)
        b.text = "$name   ${dev.address}   ${rssi}dBm"
        b.textSize = 12f
        b.setOnClickListener { connect(dev) }
        deviceBox.addView(b)
    }

    @SuppressLint("MissingPermission")
    private fun connect(dev: BluetoothDevice) {
        stopScan()
        log("连接 ${safeName(dev)} ${dev.address} …")
        try {
            gatt?.close()
        } catch (e: Exception) {
        }
        gatt = null
        target = null
        gatt = try {
            dev.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            log("connectGatt 异常：${e.message}")
            null
        }
        if (gatt == null) log("connectGatt 返回 null")
    }

    @SuppressLint("MissingPermission")
    private fun disconnect() {
        try {
            gatt?.disconnect()
        } catch (e: Exception) {
        }
        try {
            gatt?.close()
        } catch (e: Exception) {
        }
        gatt = null
        target = null
        charBox.removeAllViews()
        log("已断开")
    }

    @SuppressLint("MissingPermission")
    private fun showServices(g: BluetoothGatt) {
        charBox.removeAllViews()
        var writable = 0

        try {
            for (svc in g.services) {
                val head = TextView(this)
                head.text = "service  ${svc.uuid}"
                head.textSize = 11f
                head.setTextColor(0xFF8B949E.toInt())
                charBox.addView(head)

                for (ch in svc.characteristics) {
                    val p = ch.properties
                    val canWrite = (p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                        (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

                    val flags = StringBuilder()
                    if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) flags.append("W ")
                    if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) flags.append("WNR ")
                    if ((p and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) flags.append("N ")
                    if ((p and BluetoothGattCharacteristic.PROPERTY_READ) != 0) flags.append("R ")

                    val btn = Button(this)
                    btn.text = "${ch.uuid}  [$flags]"
                    btn.textSize = 11f
                    btn.isEnabled = canWrite
                    if (canWrite) {
                        writable++
                        btn.setOnClickListener {
                            target = ch
                            log("选中特征值 ${ch.uuid}")
                        }
                    }
                    charBox.addView(btn)
                }
            }
        } catch (e: Exception) {
            log("枚举异常：${e.message}")
        }

        log("共 ${g.services.size} 个服务，可写特征值 $writable 个")
        if (writable == 0) log("没有可写特征值 —— 可能选错设备，或需要先配对")
    }

    // ---------------- 写 BLE ----------------

    @SuppressLint("MissingPermission")
    private fun write(bytes: ByteArray, quiet: Boolean = false): Boolean {
        val g = gatt
        val ch = target
        if (g == null || ch == null) {
            if (!quiet) log("还没连接设备或没选特征值")
            return false
        }

        val type = if (noRespCheck.isChecked) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        val submitted: Boolean
        if (Build.VERSION.SDK_INT >= 33) {
            submitted = g.writeCharacteristic(ch, bytes, type) == 0 // 0 == SUCCESS
        } else {
            @Suppress("DEPRECATION")
            ch.value = bytes
            @Suppress("DEPRECATION")
            ch.writeType = type
            @Suppress("DEPRECATION")
            submitted = g.writeCharacteristic(ch)
        }

        if (!quiet) {
            log("发送 ${Protocol.hex(bytes)} → ${if (submitted) "已提交" else "提交失败"}")
        }
        return submitted
    }

    // ---------------- 小工具 ----------------

    private fun showLevel(level: Int) {
        levelText.text = level.toString()
        levelBar.progress = level
    }

    private fun maxByteValue(): Int =
        (maxByteEdit.text.toString().trim().toIntOrNull() ?: 100).coerceIn(1, 255)

    private fun safeName(dev: BluetoothDevice): String = try {
        dev.name ?: ""
    } catch (e: SecurityException) {
        ""
    }

    private fun safeEnabled(a: BluetoothAdapter): Boolean = try {
        a.isEnabled
    } catch (e: SecurityException) {
        false
    }

    private fun log(msg: String) {
        ui.post { logView.append("${clock.format(Date())}  $msg\n") }
    }
}
