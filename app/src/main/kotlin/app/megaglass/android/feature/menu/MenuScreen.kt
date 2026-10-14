package app.megaglass.android.feature.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.core.util.Format
import app.megaglass.android.feature.account.AccountViewModel
import app.megaglass.android.feature.account.planName
import app.megaglass.android.feature.settings.SettingRow
import app.megaglass.android.feature.settings.SettingsScaffold
import app.megaglass.android.feature.settings.settingsGroup
import app.megaglass.android.ui.components.Avatar
import app.megaglass.android.ui.components.ConfirmDialog
import app.megaglass.android.ui.components.Ic
import app.megaglass.android.ui.components.UsageBar
import app.megaglass.android.ui.nav.Account
import app.megaglass.android.ui.nav.Contacts
import app.megaglass.android.ui.nav.DeviceCentre
import app.megaglass.android.ui.nav.LocalNavigator
import app.megaglass.android.ui.nav.Notifications
import app.megaglass.android.ui.nav.Offline
import app.megaglass.android.ui.nav.Rubbish
import app.megaglass.android.ui.nav.Settings
import app.megaglass.android.ui.nav.SettingsAbout
import app.megaglass.android.ui.nav.Syncs
import app.megaglass.android.ui.nav.Transfers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The "Me" tab: account summary and the entry points that are not tabs. */
@Composable
fun MenuScreen(vm: AccountViewModel = viewModel()) {
    val graph = MegaGlassApp.graph
    val nav = LocalNavigator.current
    val account by vm.state.collectAsStateWithLifecycle()
    val transfers by graph.transfers.state.collectAsStateWithLifecycle()
    val offline by graph.offline.handles.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var unreadAlerts by remember { mutableIntStateOf(0) }
    var contactRequests by remember { mutableIntStateOf(0) }
    var confirmLogout by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        suspend fun refresh() {
            val (alerts, requests) = withContext(Dispatchers.IO) {
                graph.mega.api.numUnreadUserAlerts to graph.mega.api.incomingContactRequests.size
            }
            unreadAlerts = alerts
            contactRequests = requests
        }
        refresh()
        merge(graph.mega.alerts, graph.mega.contactRequests).collect { refresh() }
    }

    val active = transfers.active.size
    val filesRows = listOf(
        SettingRow.Link(
            R.drawable.ic_swap_vert,
            stringResource(R.string.menu_transfers),
            summary = if (active > 0) pluralStringResource(R.plurals.menu_transfers_active, active, active) else null,
            badge = active,
        ) { nav.navigate(Transfers) },
        SettingRow.Link(
            R.drawable.ic_offline_pin,
            stringResource(R.string.offline),
            summary = if (offline.isNotEmpty()) pluralStringResource(R.plurals.n_items, offline.size, offline.size) else null,
        ) { nav.navigate(Offline) },
        SettingRow.Link(R.drawable.ic_delete, stringResource(R.string.rubbish_bin)) { nav.navigate(Rubbish()) },
    )
    val deviceRows = listOf(
        SettingRow.Link(R.drawable.ic_devices, stringResource(R.string.menu_device_centre), summary = stringResource(R.string.menu_device_centre_summary)) {
            nav.navigate(DeviceCentre)
        },
        SettingRow.Link(R.drawable.ic_sync, stringResource(R.string.menu_sync), summary = stringResource(R.string.menu_sync_summary)) {
            nav.navigate(Syncs)
        },
    )
    val peopleRows = listOf(
        SettingRow.Link(R.drawable.ic_contacts, stringResource(R.string.menu_contacts), badge = contactRequests) { nav.navigate(Contacts) },
        SettingRow.Link(R.drawable.ic_notifications, stringResource(R.string.menu_notifications), badge = unreadAlerts) {
            nav.navigate(Notifications)
        },
    )
    val appRows = listOf(
        SettingRow.Link(R.drawable.ic_settings, stringResource(R.string.settings_title)) { nav.navigate(Settings) },
        SettingRow.Link(R.drawable.ic_info, stringResource(R.string.about_title)) { nav.navigate(SettingsAbout) },
        SettingRow.Link(R.drawable.ic_logout, stringResource(R.string.logout), destructive = true) { confirmLogout = true },
    )
    val accountName = account.fullName
    val planLabel = stringResource(planName(account.proLevel))
    val filesTitle = stringResource(R.string.menu_files)
    val devicesTitle = stringResource(R.string.menu_devices)
    val peopleTitle = stringResource(R.string.menu_people)
    val appTitle = stringResource(R.string.menu_app)

    SettingsScaffold(title = stringResource(R.string.tab_menu), showBack = false) {
        item(key = "account") {
            AccountCard(
                name = accountName,
                email = account.email,
                user = account.userHandle,
                avatarVersion = account.avatarVersion,
                plan = planLabel,
                used = account.storage?.used,
                max = account.storage?.max,
                onClick = { nav.navigate(Account) },
            )
        }
        settingsGroup(filesTitle, filesRows)
        settingsGroup(devicesTitle, deviceRows)
        settingsGroup(peopleTitle, peopleRows)
        settingsGroup(appTitle, appRows)
    }

    if (confirmLogout) {
        val warning = if (active > 0) pluralStringResource(R.plurals.logout_transfers_warning, active, active) + "\n\n" else ""
        ConfirmDialog(
            title = stringResource(R.string.logout_title),
            text = warning + stringResource(R.string.logout_text),
            confirmLabel = stringResource(R.string.logout),
            destructive = true,
            icon = R.drawable.ic_logout,
            onConfirm = { scope.launch { graph.session.logout() } },
            onDismiss = { confirmLogout = false },
        )
    }
}

@Composable
private fun AccountCard(
    name: String,
    email: String,
    user: String?,
    avatarVersion: Int,
    plan: String,
    used: Long?,
    max: Long?,
    onClick: () -> Unit,
) {
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(user = user, name = name, size = 64.dp, version = avatarVersion)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleLarge, color = onContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        email,
                        style = MaterialTheme.typography.bodyMedium,
                        color = onContainer.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Ic(R.drawable.ic_chevron_right, null, tint = onContainer)
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = onClick,
                    label = { Text(plan) },
                    leadingIcon = { Ic(R.drawable.ic_workspace_premium, null, Modifier.size(18.dp)) },
                    colors = AssistChipDefaults.assistChipColors(labelColor = onContainer, leadingIconContentColor = onContainer),
                )
                Spacer(Modifier.weight(1f))
                if (used != null && max != null && max > 0) {
                    Text(
                        stringResource(R.string.menu_storage_of, Format.size(used), Format.size(max)),
                        style = MaterialTheme.typography.labelLarge,
                        color = onContainer,
                    )
                }
            }
            if (used != null && max != null && max > 0) {
                Spacer(Modifier.height(8.dp))
                UsageBar(used.toFloat() / max)
            }
        }
    }
}
