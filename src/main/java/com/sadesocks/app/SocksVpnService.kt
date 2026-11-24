package com.sadesocks.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import hev.htproxy.TProxyService
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress

class SocksVpnService : VpnService() {

    enum class State { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    companion object {
        const val ACTION_START = "com.sadesocks.app.START"
        const val ACTION_STOP = "com.sadesocks.app.STOP"
        private const val MTU = 1500
        private const val CHANNEL_ID = "sade_socks_vpn"

        @Volatile var state: State = State.DISCONNECTED
        @Volatile var message: String = ""
    }

    private val lock = Any()
    private var tun: ParcelFileDescriptor? = null
    private var engineRunning = false
    private var session = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            stopSelf()
        } else {
            // ACTION_START, or the system starting us for Always-on VPN
            startTunnel()
        }
        return START_NOT_STICKY
    }

    private fun startTunnel() {
        var id = 0
        var alreadyRunning = false
        synchronized(lock) {
            if (state == State.CONNECTING || state == State.CONNECTED) {
                alreadyRunning = true
            } else {
                session++
                id = session
                state = State.CONNECTING
                message = "Checking server..."
            }
        }
        if (alreadyRunning) return

        showForeground()
        val cfg = Config.load(this)
        if (cfg == null) {
            fail(id, "Server is not configured. Open the app and save it first.")
            return
        }
        Thread { connect(id, cfg) }.start()
    }

    private fun connect(id: Int, cfg: Config) {
        try {
            val host = cfg.host.trim().trim('[', ']')
            val addrs = InetAddress.getAllByName(host)
            val ip = addrs.firstOrNull { it is Inet4Address } ?: addrs.first()

            // Fail early with a clear message instead of starting a dead VPN.
            Socks5Probe.check(ip, cfg.port, cfg.user, cfg.pass)

            synchronized(lock) {
                if (id != session) return
                message = "Starting tunnel..."

                val builder = Builder()
                    .setSession("Sade Socks")
                    .setMtu(MTU)
                    .addAddress("198.18.0.1", 32)
                    .addAddress("fc00::1", 128)
                    .addRoute("0.0.0.0", 0)   // all IPv4 through the tunnel
                    .addRoute("::", 0)        // all IPv6 through the tunnel (no IPv6 leak)
                    .addDnsServer("198.18.0.2") // answered inside the tunnel (no DNS leak)
                    .addDisallowedApplication(packageName) // our own socket to the server stays outside

                val pfd = builder.establish()
                    ?: throw IllegalStateException("VPN permission was not granted")
                tun = pfd

                File(filesDir, "hev.log").delete()
                val file = File(filesDir, "tunnel.yml")
                file.writeText(buildYaml(cfg, ip))
                val ok = TProxyService.TProxyStartService(file.absolutePath, pfd.fd)
                if (!ok) throw IllegalStateException("Tunnel engine failed to start (check config/log)")
                engineRunning = true

                state = State.CONNECTED
                message = "Connected"
            }
        } catch (t: Throwable) {
            fail(id, t.message ?: t.javaClass.simpleName)
        }
    }

    private fun showForeground() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "VPN status", NotificationManager.IMPORTANCE_LOW)
                )
            }
            val pi = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
            else Notification.Builder(this)
            val n = b.setContentTitle("Sade Socks")
                .setContentText("VPN is running")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
            startForeground(1, n)
        } catch (_: Throwable) {
        }
    }

    private fun q(s: String): String = "'" + s.replace("'", "''") + "'"

    private fun buildYaml(cfg: Config, ip: InetAddress): String {
        val addr = (ip.hostAddress ?: "").substringBefore('%')
        val sb = StringBuilder()
        sb.append("tunnel:\n")
        sb.append("  mtu: $MTU\n")
        sb.append("  ipv4: 198.18.0.1\n")
        sb.append("  ipv6: 'fc00::1'\n")
        sb.append("socks5:\n")
        sb.append("  port: ${cfg.port}\n")
        sb.append("  address: ${q(addr)}\n")
        sb.append("  udp: 'udp'\n")
        if (cfg.user.isNotEmpty()) {
            sb.append("  username: ${q(cfg.user)}\n")
            sb.append("  password: ${q(cfg.pass)}\n")
        }
        sb.append("mapdns:\n")
        sb.append("  address: 198.18.0.2\n")
        sb.append("  port: 53\n")
        sb.append("  network: 100.64.0.0\n")
        sb.append("  netmask: 240.0.0.0\n")
        sb.append("  cache-size: 10000\n")
        sb.append("misc:\n")
        sb.append("  task-stack-size: 81920\n")
        sb.append("  log-file: ${q(File(filesDir, "hev.log").absolutePath)}\n")
        sb.append("  log-level: info\n")
        return sb.toString()
    }

    private fun releaseResources() {
        synchronized(lock) {
            if (engineRunning) {
                try { TProxyService.TProxyStopService() } catch (_: Throwable) {}
                engineRunning = false
            }
            try { tun?.close() } catch (_: Throwable) {}
            tun = null
        }
    }

    private fun fail(id: Int, msg: String) {
        var stale = false
        synchronized(lock) {
            if (id != session) {
                stale = true
            } else {
                releaseResources()
                state = State.ERROR
                message = msg
            }
        }
        if (!stale) {
            try { stopForeground(true) } catch (_: Throwable) {}
            stopSelf()
        }
    }

    private fun stopTunnel() {
        synchronized(lock) {
            session++
            releaseResources()
            state = State.DISCONNECTED
            message = "Disconnected"
        }
        try { stopForeground(true) } catch (_: Throwable) {}
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        if (state == State.CONNECTED || state == State.CONNECTING) stopTunnel()
        super.onDestroy()
    }
}
