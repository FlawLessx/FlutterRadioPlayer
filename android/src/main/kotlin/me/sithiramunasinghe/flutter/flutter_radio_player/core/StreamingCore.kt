package me.sithiramunasinghe.flutter.flutter_radio_player.core

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.session.MediaSession
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import androidx.annotation.Nullable
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.bumptech.glide.Glide
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.metadata.icy.IcyInfo
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.util.Util
import me.sithiramunasinghe.flutter.flutter_radio_player.FlutterRadioPlayerPlugin.Companion.broadcastActionName
import me.sithiramunasinghe.flutter.flutter_radio_player.FlutterRadioPlayerPlugin.Companion.broadcastChangedMetaDataName
import me.sithiramunasinghe.flutter.flutter_radio_player.R
import me.sithiramunasinghe.flutter.flutter_radio_player.core.enums.PlaybackStatus
import java.util.concurrent.ExecutionException
import java.util.logging.Logger


class StreamingCore : Service(), AudioManager.OnAudioFocusChangeListener {

    private var logger = Logger.getLogger(StreamingCore::javaClass.name)

    private var isBound = false
    private val iBinder = LocalBinder()
    private lateinit var playbackStatus: PlaybackStatus
    private lateinit var dataSourceFactory: DefaultDataSourceFactory
    private lateinit var localBroadcastManager: LocalBroadcastManager
    private var title : String? = "-"
    private var artist : String? = null

    private var trackImage : Bitmap? = null
    private var metadata : String? = ""
    private var cover : String? = null

    // context
    private val context = this
    private val broadcastMetaDataIntent = Intent(broadcastChangedMetaDataName)

    // class instances
    private var player: SimpleExoPlayer? = null
    private var mediaSessionConnector: MediaSessionConnector? = null
    private var mediaSession: MediaSession? = null
    private var playerNotificationManager: PlayerNotificationManager? = null

    // session keys
    private val playbackNotificationId = 1
    private val mediaSessionId = "streaming_audio_player_media_session"
    private val playbackChannelId = "streaming_audio_player_channel_id"

    inner class LocalBinder : Binder() {
        internal val service: StreamingCore
            get() = this@StreamingCore
    }

    /*===========================
     *        Player APIS
     *===========================
     */

    fun play() {
        logger.info("playing audio $player ...")
        player?.playWhenReady = true
    }

    fun pause() {
        logger.info("pausing audio...")
        player?.playWhenReady = false
    }

    fun isPlaying(): Boolean {
        val isPlaying = this.playbackStatus == PlaybackStatus.PLAYING
        logger?.info("is playing status: $isPlaying")
        return isPlaying
    }

    fun stop() {
        logger.info("stopping audio $player ...")
        player?.stop()
        stopSelf()
        isBound = false
    }

    fun setVolume(volume: Double) {
        logger.info("Changing volume to : $volume")
        player?.volume = volume.toFloat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        logger.info("Firing up service. (onStartCommand)...")

        localBroadcastManager = LocalBroadcastManager.getInstance(context)

        logger.info("LocalBroadCastManager Received...")

        // get details
        val imgUrl = intent!!.getStringExtra("imgUrl")
        val streamUrl = intent.getStringExtra("streamUrl")
        val playWhenReady = intent.getStringExtra("playWhenReady") == "true"
        val stationName = intent.getStringExtra("stationName")
        val iconResourceId = intent.getIntExtra("iconResourceId", 0)

        player = SimpleExoPlayer.Builder(context).build()
        dataSourceFactory = DefaultDataSourceFactory(context, Util.getUserAgent(context, stationName!!))

        val audioSource = buildMediaSource(dataSourceFactory, streamUrl!!)

        val playerEvents = object : Player.EventListener {
            override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {

                playbackStatus = when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        pushEvent(RADIO_PLAYER_LOADING)
                        PlaybackStatus.LOADING
                    }
                    Player.STATE_IDLE -> {
                        pushEvent(RADIO_PLAYER_STOPPED)
                        PlaybackStatus.STOPPED
                    }
                    Player.STATE_READY -> {
                        setPlayWhenReady(playWhenReady)
                    }
                    Player.STATE_ENDED -> {
                        pushEvent(RADIO_PLAYER_STOPPED)
                        setPlayWhenReady(playWhenReady)
                    }
                    else -> setPlayWhenReady(playWhenReady)
                }

                logger.info("onPlayerStateChanged: $playbackStatus")
            }

            override fun onPlayerError(error: ExoPlaybackException) {
                pushEvent(RADIO_PLAYER_ERROR)
                playbackStatus = PlaybackStatus.ERROR
                logger.info("onPlayerStateChanged: $playbackStatus")
                //error.printStackTrace()
            }
        }

        // set exo player configs
        player?.let {
            it.addListener(playerEvents)
            it.playWhenReady = playWhenReady
            it.prepare(audioSource)
        }

        // register our meta data listener
        player?.addMetadataOutput {
            val icyInfo: IcyInfo = it[0] as IcyInfo
            metadata = it[0].toString()
            cover = icyInfo.url

            if(icyInfo.title != null){
                val converted : List<String> =  convertTitle(icyInfo.title!!);
                if(converted.size == 2){
                    artist = converted[0].trim();
                    title = converted[1].trim()
                }
            }

            playerNotificationManager?.invalidate()

            localBroadcastManager.sendBroadcast(broadcastMetaDataIntent.putExtra("meta_data", metadata))
        }


        playerNotificationManager = PlayerNotificationManager.createWithNotificationChannel(
                context,
                playbackChannelId,
                R.string.channel_name,
                R.string.channel_description,
                playbackNotificationId,
                object : PlayerNotificationManager.MediaDescriptionAdapter {
                    override fun getCurrentContentTitle(player: Player): String {
                        return title!!
                    }

                    @Nullable
                    override fun createCurrentContentIntent(player: Player): PendingIntent {
                        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
                    }

                    @Nullable
                    override fun getCurrentContentText(player: Player): String? {
                        return if(artist != null)
                            artist
                        else
                            stationName
                    }

                    @Nullable
                    override fun getCurrentLargeIcon(player: Player, callback: PlayerNotificationManager.BitmapCallback): Bitmap? {
                        if(cover != null){
                            return loadBitmap(cover.toString(), callback)
                        }
                        else if(imgUrl != null) {
                            return loadBitmap(imgUrl.toString(), callback)
                        }
                        else {
                            return null
                        }
                    }

                },
                object : PlayerNotificationManager.NotificationListener {
                    override fun onNotificationCancelled(notificationId: Int, dismissedByUser: Boolean) {
                        logger.info("Notification Cancelled. Stopping player...")
                        stop()
                    }

                    override fun onNotificationPosted(notificationId: Int, notification: Notification, ongoing: Boolean) {
                        logger.info("Attaching player as a foreground notification...")
                        startForeground(notificationId, notification)
                    }
                }

        )

        logger.info("Building Media Session and Player Notification.")

        val mediaSession = MediaSessionCompat(context, mediaSessionId)
        mediaSession.isActive = true

        mediaSessionConnector = MediaSessionConnector(mediaSession)
        mediaSessionConnector?.setPlayer(player)

        playerNotificationManager!!.setUseStopAction(true)
        playerNotificationManager!!.setFastForwardIncrementMs(0)
        playerNotificationManager!!.setRewindIncrementMs(0)
        playerNotificationManager!!.setUsePlayPauseActions(true)
        playerNotificationManager!!.setUseNavigationActions(false)
        playerNotificationManager!!.setUseNavigationActionsInCompactView(false)
        playerNotificationManager!!.setPlayer(player)
        playerNotificationManager!!.setPriority(NotificationCompat.PRIORITY_HIGH)
        playerNotificationManager!!.setMediaSessionToken(mediaSession.sessionToken)
        playerNotificationManager!!.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if(iconResourceId != -1)
            //playerNotificationManager.setSmallIcon(iconResourceId)

        playbackStatus = PlaybackStatus.PLAYING

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return iBinder
    }

    override fun onDestroy() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mediaSession?.release()
        }

        mediaSessionConnector?.setPlayer(null)
        playerNotificationManager?.setPlayer(null)
        player?.release()

        super.onDestroy()
    }

    override fun onAudioFocusChange(audioFocus: Int) {
        logger.info("Audio Changing: $audioFocus")

        when (audioFocus) {

            AudioManager.AUDIOFOCUS_GAIN -> {
                player?.volume = 0.8f
                play()
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                stop()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (isPlaying()) {
                    stop()
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (isPlaying()) {
                    player?.volume = 0.1f
                }
            }
        }
    }

    /**
     * Push events to local broadcaster service.
     */
    private fun pushEvent(eventName: String) {
        logger.info("Pushing Event: $eventName")
        val broadcastIntent = Intent(broadcastActionName)
        broadcastIntent.putExtra("status", eventName)
        localBroadcastManager.sendBroadcast(broadcastIntent)
    }

    /**
     * Build the media source depending of the URL content type.
     */
    private fun buildMediaSource(dataSourceFactory: DefaultDataSourceFactory, streamUrl: String): MediaSource {

        val uri = Uri.parse(streamUrl)

        return when (val type = Util.inferContentType(uri)) {
            C.TYPE_HLS -> HlsMediaSource.Factory(dataSourceFactory).createMediaSource(uri)
            C.TYPE_OTHER -> ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(uri)
            else -> {
                throw IllegalStateException("Unsupported type: $type")
            }
        }
    }

    private fun setPlayWhenReady(playWhenReady: Boolean): PlaybackStatus {
        return if (playWhenReady) {
            pushEvent(RADIO_PLAYER_PLAYING)
            PlaybackStatus.PLAYING
        } else {
            pushEvent(RADIO_PLAYER_PAUSED)
            PlaybackStatus.PAUSED
        }
    }

    private fun loadBitmap(url: String, callback: PlayerNotificationManager.BitmapCallback?): Bitmap? {
        val thread = Thread {
            try {
                val uri = Uri.parse(url)
                val bitmap = Glide.with(applicationContext)
                    .asBitmap()
                    .load(uri)
                    .submit().get()

                trackImage = bitmap
                callback?.onBitmap(bitmap)
            } catch (e: ExecutionException) {
                e.printStackTrace()
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }
        thread.start()

        return trackImage
    }

    private fun convertTitle(title: String ) : List<String>{
        var tempData = ""

        tempData = title.replace('\t', '-')
        tempData = tempData.capitalizeWords()
        val result: List<String> = tempData.split('-');

        return result;
    }

    private fun String.capitalizeWords(): String =
        split(" ").joinToString(" ") { it.toLowerCase().capitalize() }
}
