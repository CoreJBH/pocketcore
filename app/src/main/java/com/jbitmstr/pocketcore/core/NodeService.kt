package com.jbitmstr.pocketcore.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.jbitmstr.pocketcore.MainActivity
import com.jbitmstr.pocketcore.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the node alive while the app is off screen and
 * surfaces its state in an ongoing notification.
 */
class NodeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var receiverRegistered = false
    private var connectivityManager: ConnectivityManager? = null

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            NodeController.onEnvironmentChanged()
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            NodeController.onEnvironmentChanged()
        }

        override fun onLost(network: Network) {
            NodeController.onEnvironmentChanged()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            NodeController.onEnvironmentChanged()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NodeController.init(applicationContext)
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(NodeController.state.value))
        acquireWakeLock()
        registerReceivers()
        observeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NodeController.init(applicationContext)
        if (intent?.action == ACTION_STOP) {
            // The controller stops the daemon and then stops this service.
            scope.launch { NodeController.stop() }
            return START_NOT_STICKY
        }
        updateNotification(NodeController.state.value)
        if (NodeController.settings.state.value.shouldRun &&
            NodeController.state.value.status == NodeStatus.STOPPED
        ) {
            // The system recreated the service after the app process was killed.
            NodeController.start()
        }
        return START_STICKY
    }

    private fun observeState() {
        scope.launch {
            NodeController.state.collect { state ->
                updateNotification(state)
            }
        }
    }

    private fun updateNotification(state: NodeUiState) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(state)) }
    }

    private fun buildNotification(state: NodeUiState): Notification {
        val content = NotificationCard.of(state)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            // One icon only: the small icon on the left. A large icon would be
            // drawn a second time on the right of the card (One UI), which the
            // node apps this follows do not show.
            .setSmallIcon(R.drawable.ic_stat_node)
            .setSubText(content.subText)
            .setContentTitle(content.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.expanded))
            // A colorized card keeps the app's blue identity in the shade: the
            // system paints the whole card with this color instead of a generic
            // white surface, and switches to light text on top of it.
            .setColorized(true)
            .setColor(content.color)
            .setOngoing(true)
            // The card is refreshed every couple of seconds; the timestamp in the
            // header follows the last RPC reply without alerting again.
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(state.lastUpdated.takeIf { it > 0L } ?: System.currentTimeMillis())
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(mainIntent())

        val progress = content.progress
        if (progress != null) {
            builder.setProgress(NotificationCard.PROGRESS_SCALE, progress, false)
        } else {
            // Hide the bar again as soon as the node is caught up (or paused).
            builder.setProgress(0, 0, false)
        }

        if (state.status == NodeStatus.RUNNING || state.status == NodeStatus.STARTING || state.status == NodeStatus.WAITING) {
            val stopIntent = Intent(this, NodeService::class.java).setAction(ACTION_STOP)
            val stopPending = PendingIntent.getService(
                this,
                2,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, "Stop node", stopPending)
        }

        return builder.build()
    }

    private fun mainIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_node_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_node_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    @android.annotation.SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val power = getSystemService(PowerManager::class.java) ?: return
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pocketcore:node")
        lock.setReferenceCounted(false)
        lock.acquire()
        wakeLock = lock
    }

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            powerReceiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
        val manager = getSystemService(ConnectivityManager::class.java)
        connectivityManager = manager
        runCatching {
            manager?.registerDefaultNetworkCallback(networkCallback)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        if (receiverRegistered) {
            runCatching { unregisterReceiver(powerReceiver) }
            receiverRegistered = false
        }
        runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "node"
        private const val NOTIFICATION_ID = 41

        const val ACTION_STOP = "com.jbitmstr.pocketcore.action.STOP"

        fun stopService(context: Context) {
            context.stopService(Intent(context, NodeService::class.java))
        }
    }
}
