package app.megaglass.android.feature.viewer

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.megaglass.android.MainActivity
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.core.media.NodeImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Background audio playback with the system media controls and notification.
 * Cloud tracks stream through the SDK HTTP server, which stays up while the queue plays.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private val scope = MainScope()

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val sources = DefaultMediaSourceFactory(ResolvingDataSource.Factory(DefaultDataSource.Factory(this), ::resolve))
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(sources)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        // The server stops when the queue ends or fails; a new start creates new links.
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED || state == Player.STATE_IDLE) StreamServer.releasePlayback()
            }
        })
        val open = PendingIntent.getActivity(
            this, 3, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setCallback(object : MediaSession.Callback {
                // Other apps must not read the queue or control playback. Only this app, its media
                // notification and trusted controllers (system UI, Bluetooth, Android Auto) connect.
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                    if (controller.uid == Process.myUid() || controller.isTrusted ||
                        session.isMediaNotificationController(controller) ||
                        session.isAutoCompanionController(controller) || session.isAutomotiveController(controller)
                    ) {
                        MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller).build()
                    } else {
                        MediaSession.ConnectionResult.reject()
                    }
            })
            .build()
        // The app lock can change while the queue plays: the system controls follow it.
        scope.launch {
            MegaGlassApp.graph.settings.state.map { it.passcodeHash != null }.distinctUntilChanged().collect { hidden ->
                val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
                val art = if (hidden) emptyMap() else withContext(Dispatchers.IO) { ids.associateWith { PlaybackQueue[it]?.let(::cachedArt) } }
                val appName = getString(R.string.app_name)
                // One change for the whole queue: each change makes the session send the full queue.
                val updated = (0 until player.mediaItemCount).map { i ->
                    val item = player.getMediaItemAt(i)
                    val entry = PlaybackQueue[item.mediaId] ?: return@map item
                    val metadata = if (hidden) hiddenMetadata(appName) else trackMetadata(entry, art[item.mediaId])
                    item.buildUpon().setMediaMetadata(metadata).build()
                }
                if (updated.isNotEmpty()) player.replaceMediaItems(0, updated.size, updated)
            }
        }
    }

    /** A cloud track gets its link on the SDK server only when the player loads it. */
    @OptIn(UnstableApi::class)
    private fun resolve(spec: DataSpec): DataSpec {
        val id = PlaybackQueue.idOf(spec.uri) ?: return spec
        val entry = PlaybackQueue[id] ?: throw IOException("Track not found")
        val mega = MegaGlassApp.graph.mega
        val api = if (entry.folderLink) mega.folderApi else mega.api
        if (!StreamServer.holdForPlayback(api)) throw IOException("Streaming is not available")
        val link = StreamServer.link(api, entry.handle) ?: throw IOException("Track not found")
        return spec.withUri(Uri.parse(link))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        PlaybackQueue.clear()
        StreamServer.releasePlayback()
        super.onDestroy()
    }
}

/** The name of a track without its extension. */
internal val MediaEntry.title get() = name.substringBeforeLast('.')

/** What the system controls show for [entry]: its name and [art] (the thumbnail file). */
internal fun trackMetadata(entry: MediaEntry, art: Uri?): MediaMetadata = MediaMetadata.Builder()
    .setTitle(entry.title)
    .setDisplayTitle(entry.title)
    .setArtworkUri(art)
    .setIsPlayable(true)
    .build()

/**
 * A neutral title for the system controls (app lock). Empty values also replace the tags of the
 * file (artist, album, embedded cover), which the player adds to the metadata.
 */
internal fun hiddenMetadata(title: String): MediaMetadata = MediaMetadata.Builder()
    .setTitle(title)
    .setDisplayTitle(title)
    .setArtist("")
    .setAlbumTitle("")
    .setAlbumArtist("")
    .setSubtitle("")
    .setDescription("")
    .setArtworkData(ByteArray(0), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
    .setIsPlayable(true)
    .build()

/** The thumbnail of [entry] when it is in the cache. Reads the disk. */
internal fun cachedArt(entry: MediaEntry): Uri? =
    if (!entry.hasThumbnail) null
    else MegaGlassApp.graph.images.file(NodeImage(entry.handle, folderLink = entry.folderLink)).takeIf { it.length() > 0 }?.let(Uri::fromFile)

/**
 * The tracks of the audio queue, in memory. Media items carry only random ids: the session
 * shares items with its controllers, which must not see node handles or local paths.
 */
object PlaybackQueue {
    private const val SCHEME = "megaglass-track"
    private val entries = ConcurrentHashMap<String, MediaEntry>()

    /** Returns a new media id for each entry. */
    fun add(list: List<MediaEntry>): List<String> = list.map { e -> UUID.randomUUID().toString().also { entries[it] = e } }

    operator fun get(mediaId: String?): MediaEntry? = mediaId?.let(entries::get)

    /** Player URI of a cloud track; PlaybackService replaces it with a server link. */
    fun uri(mediaId: String): Uri = Uri.Builder().scheme(SCHEME).authority(mediaId).build()

    fun idOf(uri: Uri): String? = uri.authority?.takeIf { uri.scheme == SCHEME }

    fun clear() = entries.clear()
}
