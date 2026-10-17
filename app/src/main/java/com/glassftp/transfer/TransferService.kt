package com.glassftp.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.glassftp.App
import com.glassftp.MainActivity
import com.glassftp.R
import com.glassftp.core.Format

/** Keeps the process (and Wi‑Fi) alive while the queue has work, with a progress notification. */
class TransferService : Service() {
    private val listener = { update() }
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var started = false

    override fun attachBaseContext(base: Context) = super.attachBaseContext(App.localized(base))

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        App.transfers.addListener(listener)
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GlassFTP:transfers").apply { setReferenceCounted(false); acquire(6 * 3600_000L) }
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "GlassFTP:transfers")
            ?.apply { setReferenceCounted(false); acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = build()
        if (!started) {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(ID, n)
            }
            started = true
        }
        update()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        App.transfers.removeListener(listener)
        runCatching { wakeLock?.release() }
        runCatching { wifiLock?.release() }
        super.onDestroy()
    }

    private fun update() {
        if (!App.transfers.isBusy) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        getSystemService(NotificationManager::class.java).notify(ID, build())
    }

    private fun build(): Notification {
        val all = App.transfers.snapshot().filter { !it.isFolder }
        val active = all.filter { !it.state.finished }
        val running = active.filter { it.state == TransferState.RUNNING }
        val total = active.sumOf { it.total.coerceAtLeast(0) }
        val done = active.sumOf { it.done }
        val speed = running.sumOf { it.speed }
        val title = if (running.size == 1) running[0].name
            else getString(R.string.notif_transferring, active.size)
        val text = buildString {
            append(Format.size(done)).append(" / ").append(Format.size(total))
            if (speed > 0) append(" · ").append(Format.speed(speed))
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, 2)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(open)
            .setProgress(100, pct, total <= 0)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .build()
    }

    companion object {
        private const val ID = 42
        const val CHANNEL = "transfers"

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, ctx.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }
}
