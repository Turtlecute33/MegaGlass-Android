package app.megaglass.android.ui.nav

import androidx.navigation3.runtime.NavKey

/** Routes that hide the navigation bar (viewers and editors). */
interface FullScreen

/** Where a viewer gets its list of items to swipe through. */
sealed interface MediaSource {
    /** The images and videos of a cloud folder. */
    data class Folder(val parent: Long) : MediaSource
    /** All photos and videos in Cloud drive (the Photos timeline). */
    data object Timeline : MediaSource
    /** An album: a user album id, or with [system] one of the system album ids in [Album]. */
    data class Album(val id: Long, val system: Boolean = false) : MediaSource
    /** Only this item. */
    data object Single : MediaSource
    /** A folder inside a public folder link. */
    data class FolderLink(val parent: Long) : MediaSource
    /** A local file (offline files); the viewer shows the media of its folder, starting at [path]. */
    data class Local(val path: String) : MediaSource
}

sealed interface Route : NavKey

// Top-level destinations (one back stack each).
data object Home : Route
/** A cloud folder. [ROOT] is Cloud drive. Also used for incoming share folders. */
data class Folder(val handle: Long = ROOT) : Route
data object Menu : Route

data object Photos : Route
/** Shared items; [tab] 0 incoming, 1 outgoing, 2 links. */
data class Shared(val tab: Int = 0) : Route

// Cloud
data class Rubbish(val handle: Long = ROOT) : Route
/** Backups (the vault). */
data class Backups(val handle: Long = ROOT) : Route
/** Search, optionally inside one folder tree. */
data class Search(val parent: Long = ROOT) : Route
/** A file category from Home: MegaApiJava.FILE_TYPE_* (photos, audio, videos, documents). */
data class Category(val category: Int) : Route
data object Favourites : Route
data class NodeInfo(val handle: Long) : Route
data class Versions(val handle: Long) : Route
data object Transfers : Route
data object Offline : Route
/** A folder inside an offline folder. */
data class OfflineFolder(val path: String) : Route

// Photos
/**
 * A user album (MegaSet id), or with [system] a system album: [Album.FAVOURITES], [Album.GIF],
 * [Album.RAW]. Set ids use all 64 bits, so about half of them are negative.
 */
data class Album(val id: Long, val system: Boolean = false) : Route {
    companion object {
        const val FAVOURITES = -2L
        const val GIF = -3L
        const val RAW = -4L
    }
}
data object CameraUploadsSettings : Route

// People
data object Contacts : Route
data object ContactRequests : Route
data class ContactDetail(val email: String) : Route
data object Notifications : Route

/** A mega.nz file, folder, or album link. */
data class PublicLink(val url: String) : Route

// Sync and devices
data object Syncs : Route
data object DeviceCentre : Route

// Account and settings
data object Account : Route
data object Settings : Route
data object SettingsAppearance : Route
data object SettingsSecurity : Route
data object SettingsTransfers : Route
data object SettingsStorage : Route
data object SettingsAbout : Route
data object Sessions : Route
data object ChangePassword : Route
data object ChangeEmail : Route
data object TwoFactorSetup : Route
data object RecoveryKey : Route
data object PasscodeSetup : Route

// Viewers (full screen)
data class ImageViewer(val handle: Long, val source: MediaSource) : Route, FullScreen
/** [localPath] plays a local file; otherwise the node streams from MEGA. */
data class VideoPlayer(val handle: Long, val folderLink: Boolean = false, val localPath: String? = null) : Route, FullScreen
/** Plays [handle]; the other audio files of [source] form the queue. */
data class AudioPlayer(val handle: Long, val source: MediaSource = MediaSource.Single, val localPath: String? = null) : Route
data class PdfViewer(val handle: Long, val localPath: String? = null, val folderLink: Boolean = false) : Route, FullScreen
/** Full screen: a tap on the current tab would close the editor without asking about unsaved edits. */
data class TextEditor(val handle: Long, val localPath: String? = null, val folderLink: Boolean = false) : Route, FullScreen

/** Handle value for "the root of this section". */
const val ROOT = -1L

enum class Tab(val root: Route) {
    HOME(Home), DRIVE(Folder()), MENU(Menu)
}
