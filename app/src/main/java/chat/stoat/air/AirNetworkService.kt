package chat.stoat.air

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import chat.stoat.R
import chat.stoat.activities.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * AIR Chat: keeps the peer-to-peer connection alive while the app is in the background, and
 * shows a notification for new messages, since there is no server to send push notifications.
 */
class AirNetworkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        val notification = NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setLargeIcon(logo(this))
            .setContentTitle("AIR Chat")
            .setContentText(getString(R.string.air_service_running))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openAppIntent(this))
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(STATUS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(STATUS_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not start in the foreground", e)
            stopSelf()
            return
        }
        watchMessages()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun watchMessages() {
        watchJob?.cancel()
        watchJob = scope.launch {
            val seen = HashSet(AirNetwork.items.value.keys)
            AirNetwork.items.collect { items ->
                val fresh = items.values.filter { seen.add(it.id) }
                if (visibleActivities > 0) return@collect
                fresh.filter {
                    it.type == AirItem.MESSAGE && it.author != AirNetwork.selfKey &&
                            System.currentTimeMillis() - it.createdAt < 24 * 3600 * 1000L
                }.forEach { notify(it) }
            }
        }
    }

    private fun notify(item: AirItem) {
        val conversation = AirNetwork.conversations.value[item.conversation] ?: return
        val author = AirNetwork.profiles.value[item.author]?.displayName ?: AirNetwork.username(item.author)
        val title = when (conversation.kind) {
            AirConversation.KIND_DM -> author
            AirConversation.KIND_CHANNEL -> {
                val server = conversation.parent?.let { AirNetwork.conversations.value[it] }?.name ?: ""
                "$author · #${conversation.name}" + if (server.isNotEmpty()) " ($server)" else ""
            }

            else -> "$author · ${conversation.name}"
        }
        val text = item.text.ifBlank { "📎" }
        val notification = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setLargeIcon(logo(this))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openAppIntent(this))
            .setGroup(conversation.id)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(conversation.id.hashCode(), notification)
        } catch (e: SecurityException) {
            // notifications not allowed
        }
    }

    companion object {
        private const val TAG = "AirNetworkService"
        private const val CHANNEL_STATUS = "air_status"
        private const val CHANNEL_MESSAGES = "air_messages"
        private const val STATUS_ID = 4201

        @Volatile
        private var visibleActivities = 0

        @Volatile
        private var tracking = false

        /** Counts visible screens, so notifications only appear when the app is not open. */
        fun trackVisibility(application: Application) {
            if (tracking) return
            tracking = true
            application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    visibleActivities++
                }

                override fun onActivityStopped(activity: Activity) {
                    visibleActivities = maxOf(0, visibleActivities - 1)
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            })
        }

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, AirNetworkService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Could not start the background connection", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AirNetworkService::class.java))
        }

        /** The AIR Chat logo, shown in full colour in notifications. */
        private fun logo(context: Context): android.graphics.Bitmap? =
            android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.air_logo)

        private fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_STATUS, context.getString(R.string.air_channel_status), NotificationManager.IMPORTANCE_MIN)
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_MESSAGES, context.getString(R.string.air_channel_messages), NotificationManager.IMPORTANCE_HIGH)
            )
        }

        private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
