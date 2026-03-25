package org.y20k.transistor.playback

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.AudioEffect
import android.media.session.PlaybackState
import android.net.Uri
import android.os.*
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media.MediaBrowserServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.y20k.transistor.Keys
import org.y20k.transistor.R
import org.y20k.transistor.collection.CollectionProvider
import org.y20k.transistor.core.Collection
import org.y20k.transistor.core.Station
import org.y20k.transistor.helpers.*
import org.y20k.transistor.ui.PlayerState
import java.util.*
import kotlin.math.min

class PlayerService : MediaBrowserServiceCompat() {

    private val TAG: String = LogHelper.makeLogTag(PlayerService::class.java)

    private var collection: Collection = Collection()
    private var collectionProvider: CollectionProvider = CollectionProvider()
    private var station: Station = Station()
    private var isForegroundService: Boolean = false
    private lateinit var playerState: PlayerState
    private lateinit var metadataHistory: MutableList<String>
    private lateinit var packageValidator: PackageValidator
    protected lateinit var mediaSession: MediaSessionCompat
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var userAgent: String
    private lateinit var modificationDate: Date
    private lateinit var collectionChangedReceiver: BroadcastReceiver
    private lateinit var sleepTimer: CountDownTimer
    private var sleepTimerTimeRemaining: Long = 0L
    private var playbackRestartCounter: Int = 0

    private var mediaPlayer: MediaPlayer? = null
    private var currentStreamUrl: String? = null
    private var isPreparing = false
    private val playerLock = Any()

    private val wakeLock: PowerManager.WakeLock? by lazy {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Transistor:AudioWakeLock")
    }

    private val retryHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        userAgent = "Transistor"
        modificationDate = PreferencesHelper.loadCollectionModificationDate()
        packageValidator = PackageValidator(this, R.xml.allowed_media_browser_callers)
        playerState = PreferencesHelper.loadPlayerState()
        metadataHistory = PreferencesHelper.loadMetadataHistory()
        createMediaSession()
        notificationHelper = NotificationHelper(this, mediaSession.sessionToken)
        collectionChangedReceiver = createCollectionChangedReceiver()
        LocalBroadcastManager.getInstance(application).registerReceiver(collectionChangedReceiver, IntentFilter(Keys.ACTION_COLLECTION_CHANGED))
        collection = FileHelper.readCollection(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == Keys.ACTION_STOP) {
            stopPlayback()
        }
        if (intent?.action == Keys.ACTION_START) {
            when {
                intent.hasExtra(Keys.EXTRA_STATION_UUID) -> {
                    val uuid = intent.getStringExtra(Keys.EXTRA_STATION_UUID) ?: ""
                    station = CollectionHelper.getStation(collection, uuid)
                }
                intent.hasExtra(Keys.EXTRA_STREAM_URI) -> {
                    val uri = intent.getStringExtra(Keys.EXTRA_STREAM_URI) ?: ""
                    station = CollectionHelper.getStationWithStreamUri(collection, uri)
                }
                else -> {
                    station = CollectionHelper.getStation(collection, playerState.stationUuid)
                }
            }
            if (station.isValid()) preparePlayback(true)
        }
        return Service.START_STICKY_COMPATIBILITY
    }

    override fun onDestroy() {
        if (isPlaying()) handlePlaybackChange(PlaybackStateCompat.STATE_STOPPED)
        stopPlayback()
        releaseWakeLock()
        mediaSession.run {
            isActive = false
            release()
        }
        retryHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun preparePlayback(playWhenReady: Boolean) {
        if (!station.isValid()) return
        stopPlayback()
        currentStreamUrl = station.getStreamUri()
        initMediaPlayer(playWhenReady)
    }

    private fun initMediaPlayer(autoPlay: Boolean) {
        val url = currentStreamUrl ?: return
        synchronized(playerLock) {
            val mp = MediaPlayer()
            mediaPlayer = mp
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            try {
                mp.setDataSource(url)
                isPreparing = true
                mp.setOnPreparedListener {
                    synchronized(playerLock) {
                        isPreparing = false
                        if (autoPlay) {
                            mp.start()
                            acquireWakeLock()
                            handlePlaybackChange(PlaybackStateCompat.STATE_PLAYING)
                            updateMetadata(station.name)
                        }
                    }
                }
                mp.setOnErrorListener { _, what, _ ->
                    synchronized(playerLock) {
                        isPreparing = false
                        handlePlaybackChange(PlaybackStateCompat.STATE_ERROR)
                        retryPlayback()
                        true
                    }
                }
                mp.setOnCompletionListener {
                    synchronized(playerLock) {
                        handlePlaybackEnded()
                    }
                }
                mp.prepareAsync()
            } catch (e: Exception) {
                isPreparing = false
                handlePlaybackChange(PlaybackStateCompat.STATE_ERROR)
                retryPlayback()
            }
        }
    }

    private fun stopPlayback() {
        synchronized(playerLock) {
            mediaPlayer?.apply {
                try {
                    if (isPlaying) stop()
                    reset()
                    release()
                } catch (e: Exception) {}
            }
            mediaPlayer = null
            isPreparing = false
            releaseWakeLock()
            handlePlaybackChange(PlaybackStateCompat.STATE_STOPPED)
            notificationHelper.hideNotification(this)
        }
    }

    private fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true

    private fun retryPlayback() {
        retryHandler.removeCallbacksAndMessages(null)
        if (playbackRestartCounter < 5) {
            playbackRestartCounter++
            retryHandler.postDelayed({ preparePlayback(true) }, 3000)
        } else {
            Toast.makeText(this, R.string.toastmessage_error_restart_playback_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(10 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    private fun updateMetadata(metadata: String?) {
        val s = metadata?.takeIf { it.isNotEmpty() } ?: station.name
        if (metadataHistory.contains(s)) metadataHistory.removeIf { it == s }
        metadataHistory.add(s)
        if (metadataHistory.size > Keys.DEFAULT_SIZE_OF_METADATA_HISTORY) metadataHistory.removeAt(0)
        notificationHelper.updateNotification()
        PreferencesHelper.saveMetadataHistory(metadataHistory)
    }

    private fun handlePlaybackChange(state: Int) {
        playbackRestartCounter = 0
        collection = CollectionHelper.savePlaybackState(this, collection, station, state)
        updatePlayerState(station, state)
        if (state == PlaybackStateCompat.STATE_PLAYING) {
            notificationHelper.showNotification(this, station, getCurrentMetadata())
        } else {
            updateMetadata(null)
        }
    }

    private fun handlePlaybackEnded() {
        stopPlayback()
        if (playbackRestartCounter < 5) {
            playbackRestartCounter++
            preparePlayback(true)
        } else {
            Toast.makeText(this, R.string.toastmessage_error_restart_playback_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun createMediaSession() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        mediaSession = MediaSessionCompat(this, TAG).apply {
            setSessionActivity(pi)
            isActive = true
        }
        sessionToken = mediaSession.sessionToken
    }

    private fun startSleepTimer() {
        if (this::sleepTimer.isInitialized) sleepTimer.cancel()
        sleepTimer = object : CountDownTimer(Keys.SLEEP_TIMER_DURATION + sleepTimerTimeRemaining, Keys.SLEEP_TIMER_INTERVAL) {
            override fun onFinish() {
                sleepTimerTimeRemaining = 0L
                stopPlayback()
            }
            override fun onTick(millis: Long) {
                sleepTimerTimeRemaining = millis
            }
        }
        sleepTimer.start()
    }

    private fun cancelSleepTimer() {
        if (this::sleepTimer.isInitialized) {
            sleepTimer.cancel()
            sleepTimerTimeRemaining = 0L
        }
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        if (!packageValidator.isKnownCaller(clientPackageName, clientUid)) {
            return BrowserRoot(Keys.MEDIA_BROWSER_ROOT_EMPTY, null)
        }
        val extras = bundleOf(
            "android.media.browse.CONTENT_STYLE_SUPPORTED" to true,
            "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT" to 2,
            "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT" to 1
        )
        val recent = rootHints?.getBoolean(BrowserRoot.EXTRA_RECENT) == true
        return BrowserRoot(if (recent) Keys.MEDIA_BROWSER_ROOT_RECENT else Keys.MEDIA_BROWSER_ROOT, extras)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        if (!collectionProvider.isInitialized()) {
            result.detach()
            collectionProvider.retrieveMedia(this, collection, object : CollectionProvider.CollectionProviderCallback {
                override fun onStationListReady(success: Boolean) {
                    if (success) loadChildren(parentId, result)
                }
            })
        } else {
            loadChildren(parentId, result)
        }
    }

    private fun loadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        val items = ArrayList<MediaBrowserCompat.MediaItem>()
        when (parentId) {
            Keys.MEDIA_BROWSER_ROOT -> collectionProvider.stationListByName.forEach { items.add(it) }
            Keys.MEDIA_BROWSER_ROOT_EMPTY -> Unit
            else -> LogHelper.w(TAG, "Unknown parentId: $parentId")
        }
        result.sendResult(items)
    }

    private fun createCollectionChangedReceiver(): BroadcastReceiver {
        return object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val date = Date(intent.getLongExtra(Keys.EXTRA_COLLECTION_MODIFICATION_DATE, 0))
                if (date.after(collection.modificationDate)) {
                    GlobalScope.launch(Dispatchers.Main) {
                        collection = FileHelper.readCollection(context)
                    }
                }
            }
        }
    }

    private fun updatePlayerState(station: Station, state: Int) {
        if (station.isValid()) playerState.stationUuid = station.uuid
        playerState.playbackState = state
        PreferencesHelper.savePlayerState(playerState)
    }

    private fun getCurrentMetadata(): String {
        return metadataHistory.lastOrNull() ?: station.name
    }
}
