package app.megaglass.android.ui.nav

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Routes that hide the navigation bar (viewers and editors). */
interface FullScreen

/** Where a viewer gets its list of items to swipe through. */
@Serializable
sealed interface MediaSource {
    /** The images and videos of a cloud folder. */
    @Serializable data class Folder(val parent: Long) : MediaSource
    /** All photos and videos in Cloud drive (the Photos timeline). */
    @Serializable data object Timeline : MediaSource
    /** An album: a user album id, or with [system] one of the system album ids in [Album]. */
    @Serializable data class Album(val id: Long, val system: Boolean = false) : MediaSource
    /** Only this item. */
    @Serializable data object Single : MediaSource
    /** A folder inside a public folder link. */
    @Serializable data class FolderLink(val parent: Long) : MediaSource
    /** A local file (offline files); the viewer shows the media of its folder, starting at [path]. */
    @Serializable data class Local(val path: String) : MediaSource
}

@Serializable sealed interface Route : NavKey

// Top-level destinations (one back stack each).
@Serializable data object Home : Route
/** A cloud folder. [ROOT] is Cloud drive. Also used for incoming share folders. */
@Serializable data class Folder(val handle: Long = ROOT) : Route
@Serializable data object Menu : Route

@Serializable data object Photos : Route
/** Shared items; [tab] 0 incoming, 1 outgoing, 2 links. */
@Serializable data class Shared(val tab: Int = 0) : Route

// Cloud
@Serializable data class Rubbish(val handle: Long = ROOT) : Route
/** Backups (the vault). */
@Serializable data class Backups(val handle: Long = ROOT) : Route
/** Search, optionally inside one folder tree. */
@Serializable data class Search(val parent: Long = ROOT) : Route
/** A file category from Home: MegaApiJava.FILE_TYPE_* (photos, audio, videos, documents). */
@Serializable data class Category(val category: Int) : Route
@Serializable data object Favourites : Route
@Serializable data class NodeInfo(val handle: Long) : Route
@Serializable data class Versions(val handle: Long) : Route
@Serializable data object Transfers : Route
@Serializable data object Offline : Route
/** A folder inside an offline folder. */
@Serializable data class OfflineFolder(val path: String) : Route

// Photos
/**
 * A user album (MegaSet id), or with [system] a system album: [Album.FAVOURITES], [Album.GIF],
 * [Album.RAW]. Set ids use all 64 bits, so about half of them are negative.
 */
@Serializable data class Album(val id: Long, val system: Boolean = false) : Route {
    companion object {
        const val FAVOURITES = -2L
        const val GIF = -3L
        const val RAW = -4L
    }
}
@Serializable data object CameraUploadsSettings : Route

// People
@Serializable data object Contacts : Route
@Serializable data object ContactRequests : Route
@Serializable data class ContactDetail(val email: String) : Route
@Serializable data object Notifications : Route

/** A mega.nz file, folder, or album link. */
@Serializable data class PublicLink(val url: String) : Route

// Sync and devices
@Serializable data object Syncs : Route
@Serializable data object DeviceCentre : Route

// Account and settings
@Serializable data object Account : Route
@Serializable data object Settings : Route
@Serializable data object SettingsAppearance : Route
@Serializable data object SettingsSecurity : Route
@Serializable data object SettingsTransfers : Route
@Serializable data object SettingsStorage : Route
@Serializable data object SettingsAbout : Route
@Serializable data object Sessions : Route
@Serializable data object ChangePassword : Route
@Serializable data object ChangeEmail : Route
@Serializable data object TwoFactorSetup : Route
@Serializable data object RecoveryKey : Route
@Serializable data object PasscodeSetup : Route

// Viewers (full screen)
@Serializable data class ImageViewer(val handle: Long, val source: MediaSource) : Route, FullScreen
/** [localPath] plays a local file; otherwise the node streams from MEGA. */
@Serializable data class VideoPlayer(val handle: Long, val folderLink: Boolean = false, val localPath: String? = null) : Route, FullScreen
/** Plays [handle]; the other audio files of [source] form the queue. */
@Serializable data class AudioPlayer(val handle: Long, val source: MediaSource = MediaSource.Single, val localPath: String? = null) : Route
@Serializable data class PdfViewer(val handle: Long, val localPath: String? = null, val folderLink: Boolean = false) : Route, FullScreen
/** Full screen: a tap on the current tab would close the editor without asking about unsaved edits. */
@Serializable data class TextEditor(val handle: Long, val localPath: String? = null, val folderLink: Boolean = false) : Route, FullScreen

/** Handle value for "the root of this section". */
const val ROOT = -1L

enum class Tab(val root: Route) {
    HOME(Home), DRIVE(Folder()), MENU(Menu)
}
