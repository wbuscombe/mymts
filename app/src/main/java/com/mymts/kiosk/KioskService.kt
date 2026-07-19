package com.mymts.kiosk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.mymts.MainActivity
import com.mymts.util.CrashLog

/**
 * Foreground service for MyMTS's long-uptime, own-the-box ambient-wall
 * role (Model A: MyMTS is the sole kiosk on its box).
 *
 * What it does:
 *   - Runs as a foreground service with an ongoing notification, so
 *     Android keeps the process resident under memory pressure (Op Bar
 *     B-series — the wall must stay up unattended).
 *   - `START_STICKY` so the system recreates it if it's killed.
 *   - `onTaskRemoved` relaunches the wall activity so the kiosk can't be
 *     swiped/closed into a dead state.
 *
 * What it deliberately does NOT do: fight another foreground app for the
 * screen. Model A means MyMTS owns its box; the coexistence/reclaim
 * complexity (the risky Stage 1/2 two-watchdog thrash) is not built.
 *
 * **Opt-in:** nothing starts this service unless `KioskPrefs` is enabled
 * (set during provisioning). On a non-kiosk box the service is never
 * started, so the same APK is inert there (notably on a non-kiosk box).
 *
 * **STAGED:** that this actually holds the foreground across hours and
 * survives a real reboot is validated on the new box in the migration
 * session — not claimed here.
 */
class KioskService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        CrashLog.log(this, "KIOSK_SERVICE | onCreate | foreground started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Defensive: re-assert foreground in case we were recreated by the
        // system (START_STICKY redelivers a null intent).
        startAsForeground()
        // START_STICKY: if the system kills us under pressure, recreate
        // the service (with a null intent) when resources free up.
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The operator (or a misfire) swiped the task away. For an
        // unattended wall that's a dead-screen risk — relaunch the
        // activity so the kiosk recovers itself.
        if (KioskPrefs.isEnabled(this)) {
            CrashLog.log(this, "KIOSK_SERVICE | onTaskRemoved | relaunching wall")
            launchWall(this)
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun startAsForeground() {
        ensureChannel(this)
        val notification = buildNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+ requires an explicit foreground-service type. The
            // ambient wall is a long-running, device-owner display role
            // that doesn't map to a domain type (location/media/etc.),
            // so SPECIAL_USE is the honest classification — same lineage
            // as a sibling TV app's watchdog. The matching manifest declaration
            // carries the required `specialUse` property + justification.
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "mymts_kiosk"
        private const val NOTIFICATION_ID = 4201

        /**
         * Start the kiosk service iff kiosk mode is enabled on this
         * device. Safe to call unconditionally (e.g. from the activity
         * or boot receiver) — it no-ops when kiosk is off, which is the
         * gate that keeps a non-kiosk box untouched.
         */
        fun startIfEnabled(context: Context) {
            if (!KioskPrefs.isEnabled(context)) return
            val intent = Intent(context, KioskService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Stop the kiosk service (used when kiosk is disabled at runtime). */
        fun stop(context: Context) {
            context.stopService(Intent(context, KioskService::class.java))
        }

        /** Relaunch the wall activity to the front (singleTask reuses it). */
        fun launchWall(context: Context) {
            val launch = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            context.startActivity(launch)
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val mgr = context.getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MyMTS wall",
                NotificationManager.IMPORTANCE_LOW,  // silent, no sound/peek — it's ambient
            ).apply {
                description = "Keeps the MyMTS ambient wall running."
                setShowBadge(false)
            }
            mgr.createNotificationChannel(channel)
        }

        private fun buildNotification(context: Context): Notification {
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }
            return builder
                .setContentTitle("MyMTS wall")
                .setContentText("Ambient news + markets wall is running.")
                .setSmallIcon(context.applicationInfo.icon)
                .setOngoing(true)
                .build()
        }
    }
}
