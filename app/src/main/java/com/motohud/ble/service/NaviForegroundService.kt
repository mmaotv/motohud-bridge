package com.motohud.ble.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.motohud.ble.R
import com.motohud.ble.ui.navi.GuidanceActivity

/**
 * 导航前台服务：支撑「息屏导航」。
 *
 * 为什么必须有它：
 *  导航引擎靠 Handler 每秒 tick 一次，屏幕一关系统很快进入 Doze，
 *  主线程定时器会被冻结 —— 表现就是"熄屏后车机上的剩余距离不动了、
 *  也不播报了"。前台服务 + PARTIAL_WAKE_LOCK 能让进程在息屏期间
 *  继续拿到 CPU 时间，定位回调与 BLE 转发都不断。
 *
 * 只在导航进行中运行，导航结束立即 [stop]，不会常驻耗电。
 */
class NaviForegroundService : android.app.Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startAsForeground()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 每次启动都刷新一次通知（页面可能换了目的地）
        startAsForeground()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTI_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTI_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val open = Intent(this, GuidanceActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav_navigation)
            .setContentTitle(getString(R.string.navi_fg_title))
            .setContentText(getString(R.string.navi_fg_text))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setContentIntent(pi)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.navi_fg_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(ch)
    }

    @Suppress("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "motohud:navi").apply {
            setReferenceCounted(false)
            runCatching { acquire(3 * 60 * 60 * 1000L) } // 最长 3 小时，防止异常泄漏
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    companion object {
        private const val CHANNEL_ID = "navi_foreground"
        private const val NOTI_ID = 2101

        fun start(ctx: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    ctx, Intent(ctx, NaviForegroundService::class.java)
                )
            }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, NaviForegroundService::class.java)) }
        }
    }
}
