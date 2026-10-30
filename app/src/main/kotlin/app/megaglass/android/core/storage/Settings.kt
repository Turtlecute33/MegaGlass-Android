package app.megaglass.android.core.storage

import app.megaglass.android.core.util.catching

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import nz.mega.sdk.MegaApiJava

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** The screen that shows when the app opens. */
enum class StartPage { HOME, DRIVE, ME }

enum class SortOrder(val sdkOrder: Int) {
    NAME_ASC(MegaApiJava.ORDER_DEFAULT_ASC),
    NAME_DESC(MegaApiJava.ORDER_DEFAULT_DESC),
    MODIFIED_DESC(MegaApiJava.ORDER_MODIFICATION_DESC),
    MODIFIED_ASC(MegaApiJava.ORDER_MODIFICATION_ASC),
    SIZE_DESC(MegaApiJava.ORDER_SIZE_DESC),
    SIZE_ASC(MegaApiJava.ORDER_SIZE_ASC),
    LABEL(MegaApiJava.ORDER_LABEL_ASC),
    FAVOURITE(MegaApiJava.ORDER_FAV_ASC),
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val pureBlack: Boolean = false,
    val gridView: Boolean = false,
    /** Sort order of lists that are not folders (Shared items, categories); folders have their own (FolderSorts). */
    val sortOrder: SortOrder = SortOrder.NAME_ASC,
    val showHidden: Boolean = false,
    /** Absolute path of the download folder; null means Download/MegaGlass. */
    val downloadDir: String? = null,
    /** 8 parallel connections per direction instead of the SDK mobile defaults (4 down, 3 up). */
    val fastTransfers: Boolean = true,
    val cameraUploads: Boolean = false,
    val cuIncludeVideos: Boolean = true,
    val cuWifiOnly: Boolean = true,
    val cuRequireCharging: Boolean = false,
    val cuKeepFileNames: Boolean = true,
    /** Media taken before this time (ms) is not uploaded. 0 uploads everything. */
    val cuSince: Long = 0,
    /** PBKDF2 hash of the passcode, "salt:hash" in Base64; null when the lock is off. */
    val passcodeHash: String? = null,
    val passcodeLength: Int = 4,
    val biometricUnlock: Boolean = false,
    /** Seconds in the background before the app locks again. */
    val lockTimeout: Int = 0,
    /** Wrong passcodes in a row. Stored, so that closing the app does not reset the count. */
    val lockFailures: Int = 0,
    val secureScreen: Boolean = false,
    val photosGridColumns: Int = 4,
    val startPage: StartPage = StartPage.HOME,
)

private val Context.dataStore by preferencesDataStore(name = "settings")

class Settings(context: Context, scope: CoroutineScope) {

    private val store = context.dataStore

    private object K {
        val themeMode = stringPreferencesKey("theme_mode")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val pureBlack = booleanPreferencesKey("pure_black")
        val gridView = booleanPreferencesKey("grid_view")
        val sortOrder = stringPreferencesKey("sort_order")
        val showHidden = booleanPreferencesKey("show_hidden")
        val downloadDir = stringPreferencesKey("download_dir")
        val fastTransfers = booleanPreferencesKey("fast_transfers")
        val cameraUploads = booleanPreferencesKey("camera_uploads")
        val cuIncludeVideos = booleanPreferencesKey("cu_videos")
        val cuWifiOnly = booleanPreferencesKey("cu_wifi_only")
        val cuRequireCharging = booleanPreferencesKey("cu_charging")
        val cuKeepFileNames = booleanPreferencesKey("cu_keep_names")
        val cuSince = longPreferencesKey("cu_since")
        val passcodeHash = stringPreferencesKey("passcode_hash")
        val passcodeLength = intPreferencesKey("passcode_length")
        val biometricUnlock = booleanPreferencesKey("biometric_unlock")
        val lockTimeout = intPreferencesKey("lock_timeout")
        val lockFailures = intPreferencesKey("lock_failures")
        val secureScreen = booleanPreferencesKey("secure_screen")
        val photosGridColumns = intPreferencesKey("photos_columns")
        val startPage = stringPreferencesKey("start_page")
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            themeMode = this[K.themeMode]?.let { catching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.themeMode,
            dynamicColor = this[K.dynamicColor] ?: d.dynamicColor,
            pureBlack = this[K.pureBlack] ?: d.pureBlack,
            gridView = this[K.gridView] ?: d.gridView,
            sortOrder = this[K.sortOrder]?.let { catching { SortOrder.valueOf(it) }.getOrNull() } ?: d.sortOrder,
            showHidden = this[K.showHidden] ?: d.showHidden,
            downloadDir = this[K.downloadDir],
            fastTransfers = this[K.fastTransfers] ?: d.fastTransfers,
            cameraUploads = this[K.cameraUploads] ?: d.cameraUploads,
            cuIncludeVideos = this[K.cuIncludeVideos] ?: d.cuIncludeVideos,
            cuWifiOnly = this[K.cuWifiOnly] ?: d.cuWifiOnly,
            cuRequireCharging = this[K.cuRequireCharging] ?: d.cuRequireCharging,
            cuKeepFileNames = this[K.cuKeepFileNames] ?: d.cuKeepFileNames,
            cuSince = this[K.cuSince] ?: d.cuSince,
            passcodeHash = this[K.passcodeHash],
            passcodeLength = this[K.passcodeLength] ?: d.passcodeLength,
            biometricUnlock = this[K.biometricUnlock] ?: d.biometricUnlock,
            lockTimeout = this[K.lockTimeout] ?: d.lockTimeout,
            lockFailures = this[K.lockFailures] ?: d.lockFailures,
            secureScreen = this[K.secureScreen] ?: d.secureScreen,
            photosGridColumns = this[K.photosGridColumns] ?: d.photosGridColumns,
            startPage = this[K.startPage]?.let { catching { StartPage.valueOf(it) }.getOrNull() } ?: d.startPage,
        )
    }

    private fun MutablePreferences.write(s: AppSettings) {
        this[K.themeMode] = s.themeMode.name
        this[K.dynamicColor] = s.dynamicColor
        this[K.pureBlack] = s.pureBlack
        this[K.gridView] = s.gridView
        this[K.sortOrder] = s.sortOrder.name
        this[K.showHidden] = s.showHidden
        if (s.downloadDir != null) this[K.downloadDir] = s.downloadDir else remove(K.downloadDir)
        this[K.fastTransfers] = s.fastTransfers
        this[K.cameraUploads] = s.cameraUploads
        this[K.cuIncludeVideos] = s.cuIncludeVideos
        this[K.cuWifiOnly] = s.cuWifiOnly
        this[K.cuRequireCharging] = s.cuRequireCharging
        this[K.cuKeepFileNames] = s.cuKeepFileNames
        this[K.cuSince] = s.cuSince
        if (s.passcodeHash != null) this[K.passcodeHash] = s.passcodeHash else remove(K.passcodeHash)
        this[K.passcodeLength] = s.passcodeLength
        this[K.biometricUnlock] = s.biometricUnlock
        this[K.lockTimeout] = s.lockTimeout
        this[K.lockFailures] = s.lockFailures
        this[K.secureScreen] = s.secureScreen
        this[K.photosGridColumns] = s.photosGridColumns
        this[K.startPage] = s.startPage.name
    }

    /**
     * Current settings. The first value is read synchronously at startup (a few hundred bytes),
     * so that the theme and the lock are correct on the first frame.
     */
    val state: StateFlow<AppSettings> = store.data
        .map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, runBlocking { store.data.first().toSettings() })

    val current: AppSettings get() = state.value

    /** Changes the settings and returns the new values. */
    suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings =
        store.edit { prefs -> prefs.write(transform(prefs.toSettings())) }.toSettings()
}
