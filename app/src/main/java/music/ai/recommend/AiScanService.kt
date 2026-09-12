package music.ai.recommend

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import music.ai.recommend.ai.AiScanState
import music.ai.recommend.ai.AiScanner
import music.ai.recommend.ai.EmbeddingStore
import music.ai.recommend.ai.ModelRepository
import music.ai.recommend.ai.ScanStage
import music.ai.recommend.scanner.MusicScanner

/**
 * Runs the library analysis as a foreground service.
 *
 * Two reasons it cannot stay in the ViewModel. Correctness: a scan takes minutes to hours, and a
 * plain background process loses its work the moment the system reclaims it. Speed: a process that
 * is not the top app is confined to the `background` cpuset — on the test device that is CPUs 0-2 at
 * 1.9 GHz, against 0-7 at 2.4 GHz for the foreground one, and decoding measured roughly ten times
 * slower there.
 */
class AiScanService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var scanner: AiScanner? = null
    private var lastNotificationAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopScan()
            return START_NOT_STICKY
        }

        if (job?.isActive == true) return START_NOT_STICKY

        // Must happen within a few seconds of being started, before any long work.
        if (!enterForeground(getString(R.string.ai_engine_init))) {
            stopSelf()
            return START_NOT_STICKY
        }

        AiScanState.setRunning(true)
        job = scope.launch { runScan() }
        return START_NOT_STICKY
    }

    private suspend fun runScan() {
        // The scanner reports a download only once, when it starts; without this the notification
        // would sit unchanged for the length of a 280 MB fetch.
        val downloadMirror = scope.launch {
            ModelRepository.getInstance(this@AiScanService).progress.collect { download ->
                if (download.running) publish(ScanStage.DownloadingModel(download))
            }
        }
        try {
            val songs = MusicScanner().scanMusic(this).flatMap { it.songs }
            if (songs.isEmpty()) {
                AiScanState.setStage(ScanStage.Failed(REASON_NO_SONGS))
                return
            }

            val active = AiScanner(
                context = this,
                models = ModelRepository.getInstance(this),
                embeddings = EmbeddingStore.getInstance(this)
            )
            scanner = active
            active.scanSongs(songs) { stage ->
                AiScanState.setStage(stage)
                publish(stage)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Scan failed", e)
            AiScanState.setStage(ScanStage.Failed(e.message ?: "unknown"))
        } finally {
            downloadMirror.cancel()
            scanner = null
            AiScanState.setRunning(false)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopScan() {
        scanner?.stop()
        job?.cancel()
        AiScanState.setRunning(false)
        AiScanState.setStage(ScanStage.Idle)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scanner?.stop()
        scope.cancel()
        AiScanState.setRunning(false)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- notification

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.scan_channel_name),
            NotificationManager.IMPORTANCE_LOW // silent: this runs for a long time
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, progress: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, AiScanService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.scan_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.app_icon)
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop_ai_scan), stop)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply {
                if (progress in 0..100) setProgress(100, progress, false)
                else setProgress(0, 0, true)
            }
            .build()
    }

    private fun enterForeground(text: String): Boolean = try {
        val notification = buildNotification(text, -1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (e: Exception) {
        // Android refuses foreground starts from the background, and the notification permission may
        // be denied. Neither should crash the app.
        Log.e(TAG, "Could not enter the foreground", e)
        false
    }

    /** Notifications are rate limited, and a scan reports once per track. */
    private fun publish(stage: ScanStage) {
        val now = System.currentTimeMillis()
        if (now - lastNotificationAt < NOTIFICATION_INTERVAL_MS) return
        lastNotificationAt = now

        val (text, progress) = when (stage) {
            is ScanStage.DownloadingModel ->
                getString(R.string.ai_model_downloading) to (stage.progress.fraction * 100).toInt()
            is ScanStage.PreparingModel -> getString(R.string.ai_engine_init) to -1
            is ScanStage.Scanning -> {
                val percent = if (stage.total == 0) 0 else stage.done * 100 / stage.total
                getString(R.string.scan_progress, stage.done, stage.total, formatEtr(stage.etrSeconds)) to percent
            }
            is ScanStage.Failed -> getString(R.string.ai_scan_error, stage.reason) to -1
            ScanStage.Idle -> return
        }

        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(text, progress))
        }
    }

    private fun formatEtr(seconds: Long): String = "%02d:%02d".format(seconds / 60, seconds % 60)

    companion object {
        const val REASON_NO_SONGS = "no_songs"

        private const val TAG = "AiScanService"
        private const val CHANNEL_ID = "ai_scan"
        private const val NOTIFICATION_ID = 4711
        private const val NOTIFICATION_INTERVAL_MS = 1_000L
        private const val ACTION_STOP = "music.ai.recommend.STOP_SCAN"

        fun start(context: Context) {
            val intent = Intent(context, AiScanService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.e(TAG, "Could not start the scan service", it) }
        }

        fun stop(context: Context) {
            // Plain startService on purpose: startForegroundService would oblige a service that may
            // not be running to call startForeground, which it never would.
            val intent = Intent(context, AiScanService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
                .onFailure { Log.e(TAG, "Could not stop the scan service", it) }
        }
    }
}
