package com.cwtrainer.app.listen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.cwtrainer.app.CwApp
import com.cwtrainer.app.R
import com.cwtrainer.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps a listening session alive with the screen off.
 *
 * This class is only the Android shell around [ListeningController]:
 *  - foreground service of type mediaPlayback, with a media-style notification
 *    (play/pause, skip, stop) tied to a MediaSession, so lock-screen and headset buttons work
 *  - audio focus: pause on loss, resume after a transient loss, duck for short interruptions
 *  - pause when headphones are unplugged (ACTION_AUDIO_BECOMING_NOISY)
 *  - a partial wake lock while sound is playing, because AudioTrack does not hold one itself
 */
class ListeningService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val listening: ListeningController get() = (application as CwApp).container.listening   // not "controller": MediaSession has its own

    private lateinit var audioManager: AudioManager
    private lateinit var notifications: NotificationManager
    private lateinit var session: MediaSession
    private var focusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var noisyRegistered = false
    private var pausedByFocus = false
    private var sessionStarted = false
    private var lastShown: Pair<ListenStatus, Int>? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pausedByFocus = false
                listening.pause()
            }
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                pausedByFocus = false
                listening.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (listening.state.value.status == ListenStatus.PLAYING) {
                    pausedByFocus = true
                    listening.pause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> listening.duck(true)
            AudioManager.AUDIOFOCUS_GAIN -> {
                listening.duck(false)
                if (pausedByFocus) {
                    pausedByFocus = false
                    listening.resume()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notifications = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Listening playback", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Controls for background listening sessions"
                setShowBadge(false)
            },
        )
        session = MediaSession(this, "CwTrainerListening").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = play()
                override fun onPause() { pausedByFocus = false; listening.pause() }
                override fun onSkipToNext() = listening.skip()
                override fun onStop() = listening.stop()
            })
        }
        scope.launch { listening.state.collect { onState(it) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // Must become a foreground service within a few seconds of being started.
                val minutes = intent.getIntExtra(EXTRA_MINUTES, 20)
                val preview = ListenState(ListenStatus.PLAYING, 0, minutes * 60)
                goForeground(buildNotification(preview))
                if (requestFocus()) {
                    sessionStarted = true
                    listening.start(minutes, intent.getIntExtra(EXTRA_GAP, 3), intent.getBooleanExtra(EXTRA_REVEAL, true))
                } else {
                    shutDown()
                }
            }
            ACTION_PLAY -> play()
            ACTION_PAUSE -> { pausedByFocus = false; listening.pause() }
            ACTION_SKIP -> listening.skip()
            ACTION_STOP -> { listening.stop(); if (!sessionStarted) shutDown() }
            else -> if (!sessionStarted) shutDown()
        }
        return START_NOT_STICKY
    }

    private fun play() {
        if (listening.state.value.status == ListenStatus.PAUSED && requestFocus()) listening.resume()
    }

    private fun onState(state: ListenState) {
        if (!sessionStarted) return
        when (state.status) {
            ListenStatus.PLAYING -> {
                session.isActive = true
                registerNoisy(true)
                holdWakeLock(true)
            }
            ListenStatus.PAUSED -> {
                registerNoisy(false)
                holdWakeLock(false)
            }
            ListenStatus.IDLE, ListenStatus.FINISHED -> {
                shutDown()
                return
            }
        }
        // Refresh the notification when the status or the minute changes, not every second.
        val key = state.status to state.elapsedSec / 60
        if (key != lastShown) {
            lastShown = key
            updateSession(state)
            notifications.notify(NOTIFICATION_ID, buildNotification(state))
        }
    }

    private fun shutDown() {
        sessionStarted = false
        registerNoisy(false)
        holdWakeLock(false)
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        session.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (sessionStarted) listening.stop()
        registerNoisy(false)
        holdWakeLock(false)
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        session.release()
        scope.cancel()
        super.onDestroy()
    }

    /** The user swiped the app away from recents: stop rather than play on invisibly. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        listening.stop()
        super.onTaskRemoved(rootIntent)
    }

    // ---- audio focus, noisy receiver, wake lock ------------------------------------------------

    private fun requestFocus(): Boolean {
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusListener)
            .setWillPauseWhenDucked(false)
            .build()
            .also { focusRequest = it }
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun registerNoisy(on: Boolean) {
        if (on == noisyRegistered) return
        if (on) {
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(noisyReceiver, filter)
            }
        } else {
            unregisterReceiver(noisyReceiver)
        }
        noisyRegistered = on
    }

    private fun holdWakeLock(on: Boolean) {
        if (on) {
            val lock = wakeLock ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cwtrainer:listening")
                .also { it.setReferenceCounted(false); wakeLock = it }
            // Longer than the longest session; released as soon as playback pauses or ends.
            if (!lock.isHeld) lock.acquire(45 * 60 * 1000L)
        } else {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
    }

    // ---- media session and notification --------------------------------------------------------

    private fun updateSession(state: ListenState) {
        val playing = state.status == ListenStatus.PLAYING
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_STOP,
                )
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    state.elapsedSec * 1000L,
                    if (playing) 1f else 0f,
                )
                .build(),
        )
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "CW listening")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "${state.totalSec / 60}-minute session")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, state.totalSec * 1000L)
                .build(),
        )
    }

    private fun command(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this, requestCode, Intent(this, ListeningService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(icon: Int, title: String, action: String, requestCode: Int): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(this, icon), title, command(action, requestCode)).build()

    private fun buildNotification(state: ListenState): Notification {
        val playing = state.status == ListenStatus.PLAYING
        val open = PendingIntent.getActivity(
            this, 10,
            Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_LISTENING)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_cw)
            .setContentTitle(if (playing) "CW listening" else "CW listening (paused)")
            .setContentText("${state.elapsedSec / 60} of ${state.totalSec / 60} min")
            .setContentIntent(open)
            .setDeleteIntent(command(ACTION_STOP, 4))
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(
                if (playing) action(R.drawable.ic_media_pause, "Pause", ACTION_PAUSE, 1)
                else action(R.drawable.ic_media_play, "Play", ACTION_PLAY, 1),
            )
            .addAction(action(R.drawable.ic_media_skip, "Skip", ACTION_SKIP, 2))
            .addAction(action(R.drawable.ic_media_stop, "Stop", ACTION_STOP, 3))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .build()
    }

    private fun goForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "listening"
        private const val NOTIFICATION_ID = 41
        private const val ACTION_START = "com.cwtrainer.app.listen.START"
        private const val ACTION_PLAY = "com.cwtrainer.app.listen.PLAY"
        private const val ACTION_PAUSE = "com.cwtrainer.app.listen.PAUSE"
        private const val ACTION_SKIP = "com.cwtrainer.app.listen.SKIP"
        private const val ACTION_STOP = "com.cwtrainer.app.listen.STOP"
        private const val EXTRA_MINUTES = "minutes"
        private const val EXTRA_GAP = "gap"
        private const val EXTRA_REVEAL = "reveal"

        /** Resumes a paused session (the service is still running while paused). */
        fun resume(context: Context) {
            context.startService(Intent(context, ListeningService::class.java).setAction(ACTION_PLAY))
        }

        /** Starts a session. Call from a visible screen (Android only allows that). */
        fun start(context: Context, minutes: Int, gapSec: Int, reveal: Boolean) {
            val intent = Intent(context, ListeningService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_MINUTES, minutes).putExtra(EXTRA_GAP, gapSec).putExtra(EXTRA_REVEAL, reveal)
            context.startForegroundService(intent)
        }
    }
}
