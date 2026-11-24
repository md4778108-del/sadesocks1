package com.sadesocks.app

import android.Manifest
import android.app.Activity
import android.os.Build
import java.io.File
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val reqVpn = 100
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var btnConnect: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView

    private val poll = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etHost = findViewById(R.id.etHost)
        etPort = findViewById(R.id.etPort)
        etUser = findViewById(R.id.etUser)
        etPass = findViewById(R.id.etPass)
        btnConnect = findViewById(R.id.btnConnect)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 200)
        }

        val sp = Config.prefs(this)
        etHost.setText(sp.getString("host", ""))
        val savedPort = sp.getInt("port", 0)
        if (savedPort > 0) etPort.setText(savedPort.toString())
        etUser.setText(sp.getString("user", ""))
        etPass.setText(sp.getString("pass", ""))

        btnConnect.setOnClickListener { onConnectClicked() }
        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Settings > Apps > Sade Socks > Battery > Unrestricted", Toast.LENGTH_LONG).show()
            }
        }
        findViewById<Button>(R.id.btnLog).setOnClickListener { showLog() }
        findViewById<Button>(R.id.btnVpnSettings).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Open Settings > Network > VPN manually", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(poll)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll)
    }

    private fun onConnectClicked() {
        val st = SocksVpnService.state
        if (st == SocksVpnService.State.CONNECTED || st == SocksVpnService.State.CONNECTING) {
            startService(Intent(this, SocksVpnService::class.java).setAction(SocksVpnService.ACTION_STOP))
            return
        }

        val host = etHost.text.toString().trim()
        val port = etPort.text.toString().trim().toIntOrNull() ?: 0
        if (host.isEmpty()) {
            Toast.makeText(this, "Enter server IP or host", Toast.LENGTH_SHORT).show()
            return
        }
        if (port !in 1..65535) {
            Toast.makeText(this, "Enter a valid port (1-65535)", Toast.LENGTH_SHORT).show()
            return
        }
        Config.save(this, Config(host, port, etUser.text.toString(), etPass.text.toString()))

        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, reqVpn)
        } else {
            launchVpn()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqVpn) {
            if (resultCode == RESULT_OK) {
                launchVpn()
            } else {
                Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchVpn() {
        startService(Intent(this, SocksVpnService::class.java).setAction(SocksVpnService.ACTION_START))
    }

    private fun showLog() {
        val f = File(filesDir, "hev.log")
        val text = try {
            if (f.exists()) f.readText().takeLast(3000) else "(no engine log yet)"
        } catch (e: Exception) {
            "(cannot read log: ${e.message})"
        }
        tvLog.text = "State: ${SocksVpnService.state} / ${SocksVpnService.message}\n\n$text"
    }

    private fun render() {
        val st = SocksVpnService.state
        val msg = SocksVpnService.message
        val editable = st == SocksVpnService.State.DISCONNECTED || st == SocksVpnService.State.ERROR

        tvStatus.text = when (st) {
            SocksVpnService.State.DISCONNECTED -> "Disconnected"
            SocksVpnService.State.CONNECTING -> msg.ifEmpty { "Connecting..." }
            SocksVpnService.State.CONNECTED -> "Connected"
            SocksVpnService.State.ERROR -> "Error: $msg"
        }
        btnConnect.text = if (editable) "Connect" else "Disconnect"
        etHost.isEnabled = editable
        etPort.isEnabled = editable
        etUser.isEnabled = editable
        etPass.isEnabled = editable
    }
}
