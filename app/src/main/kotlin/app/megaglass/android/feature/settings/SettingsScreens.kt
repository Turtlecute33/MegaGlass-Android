package app.megaglass.android.feature.settings

import app.megaglass.android.core.util.catching

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.megaglass.android.BuildConfig
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.core.mega.await
import app.megaglass.android.core.mega.awaitResult
import app.megaglass.android.core.storage.AppSettings
import app.megaglass.android.core.storage.StartPage
import app.megaglass.android.core.storage.ThemeMode
import app.megaglass.android.core.util.Format
import app.megaglass.android.ui.components.ConfirmDialog
import app.megaglass.android.ui.components.Ic
import app.megaglass.android.ui.components.SectionHeader
import app.megaglass.android.ui.components.rememberStorageAccess
import app.megaglass.android.ui.nav.Account
import app.megaglass.android.ui.nav.CameraUploadsSettings
import app.megaglass.android.ui.nav.LocalNavigator
import app.megaglass.android.ui.nav.SettingsAbout
import app.megaglass.android.ui.nav.SettingsAppearance
import app.megaglass.android.ui.nav.SettingsSecurity
import app.megaglass.android.ui.nav.SettingsStorage
import app.megaglass.android.ui.nav.SettingsTransfers
import app.megaglass.android.ui.nav.Syncs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nz.mega.sdk.MegaError
import java.io.File

/** Changes one setting in the background. */
private fun updateSettings(transform: (AppSettings) -> AppSettings) {
    val graph = MegaGlassApp.graph
    graph.scope.launch { graph.settings.update(transform) }
}

@Composable
fun SettingsScreen() {
    val nav = LocalNavigator.current
    val general = listOf(
        SettingRow.Link(R.drawable.ic_palette, stringResource(R.string.settings_appearance), summary = stringResource(R.string.settings_appearance_summary)) {
            nav.navigate(SettingsAppearance)
        },
        SettingRow.Link(R.drawable.ic_photo_camera, stringResource(R.string.settings_camera_uploads), summary = stringResource(R.string.settings_camera_uploads_summary)) {
            nav.navigate(CameraUploadsSettings)
        },
        SettingRow.Link(R.drawable.ic_swap_vert, stringResource(R.string.settings_transfers), summary = stringResource(R.string.settings_transfers_summary)) {
            nav.navigate(SettingsTransfers)
        },
        SettingRow.Link(R.drawable.ic_sync, stringResource(R.string.menu_sync), summary = stringResource(R.string.menu_sync_summary)) {
            nav.navigate(Syncs)
        },
        SettingRow.Link(R.drawable.ic_storage, stringResource(R.string.settings_storage), summary = stringResource(R.string.settings_storage_summary)) {
            nav.navigate(SettingsStorage)
        },
    )
    val privacy = listOf(
        SettingRow.Link(R.drawable.ic_shield, stringResource(R.string.settings_security), summary = stringResource(R.string.settings_security_summary)) {
            nav.navigate(SettingsSecurity)
        },
        SettingRow.Link(R.drawable.ic_manage_accounts, stringResource(R.string.account_title), summary = stringResource(R.string.settings_account_summary)) {
            nav.navigate(Account)
        },
    )
    val about = listOf(
        SettingRow.Link(R.drawable.ic_info, stringResource(R.string.about_title), summary = stringResource(R.string.settings_about_summary, BuildConfig.VERSION_NAME)) {
            nav.navigate(SettingsAbout)
        },
    )
    val generalTitle = stringResource(R.string.settings_general)
    val privacyTitle = stringResource(R.string.settings_privacy)
    SettingsScaffold(title = stringResource(R.string.settings_title)) {
        settingsGroup(generalTitle, general)
        settingsGroup(privacyTitle, privacy)
        settingsGroup(null, about)
    }
}

@Composable
fun AppearanceSettingsScreen() {
    val settings by MegaGlassApp.graph.settings.state.collectAsStateWithLifecycle()
    val displayTitle = stringResource(R.string.settings_display)
    val display = listOf(
        SettingRow.Toggle(R.drawable.ic_palette, stringResource(R.string.settings_dynamic_color), stringResource(R.string.settings_dynamic_color_summary), settings.dynamicColor) { on ->
            updateSettings { it.copy(dynamicColor = on) }
        },
        SettingRow.Toggle(
            R.drawable.ic_dark_mode,
            stringResource(R.string.settings_pure_black),
            stringResource(R.string.settings_pure_black_summary),
            settings.pureBlack,
            enabled = settings.themeMode != ThemeMode.LIGHT,
        ) { on -> updateSettings { it.copy(pureBlack = on) } },
        SettingRow.Toggle(R.drawable.ic_visibility, stringResource(R.string.settings_show_hidden), stringResource(R.string.settings_show_hidden_summary), settings.showHidden) { on ->
            updateSettings { it.copy(showHidden = on) }
        },
    )
    SettingsScaffold(title = stringResource(R.string.settings_appearance)) {
        item(key = "theme") {
            SectionHeader(stringResource(R.string.settings_theme))
            ConnectedChoice(
                options = listOf(
                    Triple(R.drawable.ic_brightness_auto, R.string.settings_theme_system, ThemeMode.SYSTEM),
                    Triple(R.drawable.ic_light_mode, R.string.settings_theme_light, ThemeMode.LIGHT),
                    Triple(R.drawable.ic_dark_mode, R.string.settings_theme_dark, ThemeMode.DARK),
                ),
                selected = settings.themeMode,
                onSelect = { mode -> updateSettings { it.copy(themeMode = mode) } },
            )
        }
        item(key = "start") {
            SectionHeader(stringResource(R.string.settings_start_page))
            ConnectedChoice(
                options = listOf(
                    Triple(R.drawable.ic_home, R.string.tab_home, StartPage.HOME),
                    Triple(R.drawable.ic_cloud, R.string.tab_drive, StartPage.DRIVE),
                    Triple(R.drawable.ic_account_circle, R.string.tab_menu, StartPage.ME),
                ),
                selected = settings.startPage,
                onSelect = { page -> updateSettings { it.copy(startPage = page) } },
            )
        }
        settingsGroup(displayTitle, display)
        item(key = "columns") {
            SectionHeader(stringResource(R.string.settings_photo_columns))
            ConnectedChoice(
                options = listOf(3, 4, 5, 6).map { Triple(R.drawable.ic_grid_view, null, it) },
                selected = settings.photosGridColumns,
                onSelect = { n -> updateSettings { it.copy(photosGridColumns = n) } },
                label = { it.toString() },
            )
        }
    }
}

/** Expressive connected button group for one choice out of a few. */
@Composable
private fun <T> ConnectedChoice(
    options: List<Triple<Int, Int?, T>>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String = { "" },
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { i, (icon, text, value) ->
            val shapes = when (i) {
                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
            }
            ToggleButton(
                checked = value == selected,
                onCheckedChange = { onSelect(value) },
                shapes = shapes,
                modifier = Modifier.weight(1f).height(52.dp),
            ) {
                if (text != null) {
                    Ic(icon, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(text), maxLines = 1)
                } else {
                    Text(label(value), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
fun TransferSettingsScreen() {
    val graph = MegaGlassApp.graph
    val api = graph.mega.api
    val context = LocalContext.current
    val resources = LocalResources.current
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val storage = rememberStorageAccess()
    val scope = rememberCoroutineScope()
    var versioning by remember { mutableStateOf<Boolean?>(null) }
    var purgeDays by remember { mutableStateOf<Int?>(null) }
    var choosePurge by remember { mutableStateOf(false) }
    var chooseLocation by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val (code, request) = api.awaitResult { getFileVersionsOption(it) }
            // ENOENT: never set, so versioning is on (the server default).
            versioning = when (code) {
                MegaError.API_OK -> !request.flag
                MegaError.API_ENOENT -> true
                else -> null
            }
            val (pCode, pRequest) = api.awaitResult { getRubbishBinAutopurgePeriod(it) }
            purgeDays = if (pCode == MegaError.API_OK) pRequest.number.toInt() else null
        }
    }

    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val path = graph.files.realPath(uri)
        if (path == null || !File(path).canWrite()) {
            graph.messenger.show(resources.getString(R.string.settings_location_failed))
        } else {
            updateSettings { it.copy(downloadDir = path) }
        }
    }

    val speedRows = listOf(
        SettingRow.Toggle(
            R.drawable.ic_bolt,
            stringResource(R.string.settings_fast_transfers),
            stringResource(if (settings.fastTransfers) R.string.settings_fast_on else R.string.settings_fast_off),
            settings.fastTransfers,
        ) { on -> updateSettings { it.copy(fastTransfers = on) } },
        SettingRow.Toggle(
            R.drawable.ic_lock,
            stringResource(R.string.settings_https),
            stringResource(R.string.settings_https_summary),
            settings.httpsTransfers,
        ) { on -> updateSettings { it.copy(httpsTransfers = on) } },
    )
    val location = settings.downloadDir?.takeIf { graph.files.hasAllFilesAccess } ?: graph.files.defaultDownloadDir().absolutePath
    val locationRows = listOf(
        SettingRow.Link(R.drawable.ic_folder, stringResource(R.string.settings_download_location), summary = location) { chooseLocation = true },
    )
    val cloudRows = listOf(
        SettingRow.Toggle(
            R.drawable.ic_history,
            stringResource(R.string.settings_versioning),
            stringResource(R.string.settings_versioning_summary),
            versioning == true,
            enabled = versioning != null,
        ) { on ->
            versioning = on
            scope.launch {
                catching { withContext(Dispatchers.IO) { api.await { setFileVersionsOption(!on, it) } } }
                    .onFailure { versioning = !on; graph.messenger.show(it.message.orEmpty()) }
            }
        },
        SettingRow.Link(
            R.drawable.ic_auto_delete,
            stringResource(R.string.settings_rubbish_clean),
            summary = purgeDays?.let { d ->
                if (d == 0) resources.getString(R.string.settings_rubbish_clean_off)
                else context.resources.getQuantityString(R.plurals.settings_days, d, d)
            } ?: stringResource(R.string.settings_rubbish_clean_summary),
        ) { choosePurge = true },
    )
    val speedTitle = stringResource(R.string.settings_speed)
    val locationTitle = stringResource(R.string.settings_downloads)
    val cloudTitle = stringResource(R.string.settings_cloud)
    SettingsScaffold(title = stringResource(R.string.settings_transfers)) {
        settingsGroup(speedTitle, speedRows)
        settingsGroup(locationTitle, locationRows)
        if (!storage.granted) {
            item(key = "access") {
                NoticeCard(R.drawable.ic_sd_card, stringResource(R.string.settings_location_access), Modifier.padding(16.dp))
            }
        }
        settingsGroup(cloudTitle, cloudRows)
    }

    if (chooseLocation) {
        ChoiceDialog(
            title = stringResource(R.string.settings_download_location),
            options = listOf(stringResource(R.string.settings_location_default), stringResource(R.string.settings_location_choose)),
            selected = if (settings.downloadDir == null) 0 else 1,
            onSelect = { i ->
                if (i == 0) updateSettings { it.copy(downloadDir = null) }
                else storage.ensure { folder.launch(null) }
            },
            onDismiss = { chooseLocation = false },
        )
    }
    if (choosePurge) {
        val options = listOf(0, 7, 14, 30, 60, 90, 180, 365)
        ChoiceDialog(
            title = stringResource(R.string.settings_rubbish_clean),
            options = options.map {
                if (it == 0) stringResource(R.string.settings_rubbish_clean_off) else pluralStringResource(R.plurals.settings_days, it, it)
            },
            selected = options.indexOf(purgeDays ?: 30),
            onSelect = { i ->
                val days = options[i]
                scope.launch {
                    catching { withContext(Dispatchers.IO) { api.await { setRubbishBinAutopurgePeriod(days, it) } } }
                        .onSuccess { purgeDays = days }
                        .onFailure { graph.messenger.show(resources.getString(R.string.settings_rubbish_clean_pro)) }
                }
            },
            onDismiss = { choosePurge = false },
        )
    }
}

@Composable
fun StorageSettingsScreen() {
    val graph = MegaGlassApp.graph
    val api = graph.mega.api
    val scope = rememberCoroutineScope()
    var cacheSize by remember { mutableLongStateOf(-1) }
    var offlineSize by remember { mutableLongStateOf(-1) }
    var versionsSize by remember { mutableLongStateOf(-1) }
    var reload by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<StorageAction?>(null) }
    val transfers by graph.transfers.state.collectAsStateWithLifecycle()

    LaunchedEffect(reload) {
        withContext(Dispatchers.IO) {
            cacheSize = graph.images.sizeOnDisk() + listOf("open", "preview", "links").sumOf { name ->
                File(graph.context.cacheDir, name).walkTopDown().filter { it.isFile }.sumOf { it.length() }
            }
            offlineSize = graph.offline.sizeOnDisk()
        }
        versionsSize = catching {
            withContext(Dispatchers.IO) { api.await { getSpecificAccountDetails(true, false, false, it) }.megaAccountDetails.versionStorageUsed }
        }.getOrDefault(-1)
    }

    fun size(v: Long) = if (v < 0) "…" else Format.size(v)
    val deviceRows = listOf(
        SettingRow.Link(R.drawable.ic_cleaning_services, stringResource(R.string.settings_clear_cache), summary = stringResource(R.string.settings_cache_summary, size(cacheSize))) {
            confirm = StorageAction.CACHE
        },
        SettingRow.Link(R.drawable.ic_offline_pin, stringResource(R.string.settings_clear_offline), summary = stringResource(R.string.settings_offline_summary, size(offlineSize))) {
            confirm = StorageAction.OFFLINE
        },
    )
    val cloudRows = listOf(
        SettingRow.Link(
            R.drawable.ic_delete_sweep,
            stringResource(R.string.settings_delete_versions),
            summary = stringResource(R.string.settings_versions_summary, size(versionsSize)),
            enabled = versionsSize != 0L,
        ) { confirm = StorageAction.VERSIONS },
    )
    val deviceTitle = stringResource(R.string.settings_on_device)
    val cloudTitle = stringResource(R.string.settings_cloud)
    SettingsScaffold(title = stringResource(R.string.settings_storage)) {
        settingsGroup(deviceTitle, deviceRows)
        settingsGroup(cloudTitle, cloudRows)
    }

    confirm?.let { action ->
        ConfirmDialog(
            title = stringResource(action.title),
            text = stringResource(action.text),
            confirmLabel = stringResource(action.confirm),
            destructive = action != StorageAction.CACHE,
            icon = action.icon,
            onConfirm = {
                scope.launch {
                    catching {
                        when (action) {
                            StorageAction.CACHE -> withContext(Dispatchers.IO) {
                                graph.images.clear()
                                // Files in "open" can be in use by the app that is viewing them; they are copies only.
                                if (graph.transfers.state.value.isIdle) {
                                    listOf("open", "preview", "links").forEach { File(graph.context.cacheDir, it).deleteRecursively() }
                                }
                                coil3.SingletonImageLoader.get(graph.context).memoryCache?.clear()
                            }
                            StorageAction.OFFLINE -> graph.offline.clear()
                            StorageAction.VERSIONS -> graph.nodes.removeAllVersions()
                        }
                    }.onFailure { graph.messenger.show(it.message.orEmpty()) }
                    reload++
                }
            },
            onDismiss = { confirm = null },
        )
    }
}

private enum class StorageAction(val title: Int, val text: Int, val confirm: Int, val icon: Int) {
    CACHE(R.string.settings_clear_cache, R.string.settings_clear_cache_text, R.string.settings_clear, R.drawable.ic_cleaning_services),
    OFFLINE(R.string.settings_clear_offline, R.string.settings_clear_offline_text, R.string.settings_clear, R.drawable.ic_offline_pin),
    VERSIONS(R.string.settings_delete_versions, R.string.settings_delete_versions_text, R.string.settings_delete, R.drawable.ic_delete_sweep),
}

@Composable
fun AboutScreen() {
    val graph = MegaGlassApp.graph
    var licence by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val resources = LocalResources.current
    val sdkVersion = remember { graph.mega.api.version.orEmpty() }
    val versionRows = listOf(
        SettingRow.Info(R.drawable.ic_info, stringResource(R.string.about_app_version), BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"),
        SettingRow.Info(R.drawable.ic_code, stringResource(R.string.about_sdk_version), sdkVersion),
    )
    val licenceRows = listOf(
        SettingRow.Link(R.drawable.ic_gavel, "MEGA SDK", summary = "BSD 2-Clause") {
            licence = context.resources.openRawResource(R.raw.license_mega_sdk).bufferedReader().use { it.readText() }
        },
        SettingRow.Info(R.drawable.ic_gavel, "Material Symbols", "Apache License 2.0 · Google"),
        SettingRow.Info(R.drawable.ic_gavel, "AndroidX, Jetpack Compose, Media3", "Apache License 2.0 · The Android Open Source Project"),
        SettingRow.Info(R.drawable.ic_gavel, "Kotlin, kotlinx.coroutines, kotlinx.serialization", "Apache License 2.0 · JetBrains"),
        SettingRow.Info(R.drawable.ic_gavel, "Coil", "Apache License 2.0 · Coil Contributors"),
        SettingRow.Info(R.drawable.ic_gavel, "Telephoto", "Apache License 2.0 · Saket Narayan"),
        SettingRow.Info(R.drawable.ic_gavel, "Crypto++, libsodium, curl, OpenSSL, ICU, SQLite, libuv, MediaInfo", stringResource(R.string.about_native_licences)),
    )
    val versionTitle = stringResource(R.string.about_versions)
    val licenceTitle = stringResource(R.string.about_licences)
    SettingsScaffold(title = stringResource(R.string.about_title)) {
        item(key = "hero") {
            Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(112.dp).clip(MaterialShapes.Cookie12Sided.toShape()).background(Color(0xFFD21F24)),
                    contentAlignment = Alignment.Center,
                ) { Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(152.dp)) }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(key = "privacy") {
            NoticeCard(R.drawable.ic_privacy_tip, stringResource(R.string.about_privacy), Modifier.padding(horizontal = 16.dp))
        }
        settingsGroup(versionTitle, versionRows)
        settingsGroup(licenceTitle, licenceRows)
    }

    licence?.let { text ->
        AlertDialog(
            onDismissRequest = { licence = null },
            title = { Text("MEGA SDK") },
            text = {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { licence = null }) { Text(stringResource(R.string.close)) } },
        )
    }
}
