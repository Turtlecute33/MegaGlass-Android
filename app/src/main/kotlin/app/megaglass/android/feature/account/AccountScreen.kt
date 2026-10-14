package app.megaglass.android.feature.account

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.megaglass.android.R
import app.megaglass.android.core.util.Format
import app.megaglass.android.feature.auth.OtpField
import app.megaglass.android.feature.settings.SettingRow
import app.megaglass.android.feature.settings.SettingsScaffold
import app.megaglass.android.feature.settings.settingsGroup
import app.megaglass.android.feature.settings.twoFactorEnabled
import app.megaglass.android.ui.components.Avatar
import app.megaglass.android.ui.components.Ic
import app.megaglass.android.ui.components.SectionHeader
import app.megaglass.android.ui.components.UsageBar
import app.megaglass.android.ui.nav.ChangeEmail
import app.megaglass.android.ui.nav.LocalNavigator
import app.megaglass.android.ui.theme.AppTheme
import kotlinx.coroutines.launch
import nz.mega.sdk.MegaAccountDetails

@Composable
fun AccountScreen(vm: AccountViewModel = viewModel()) {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val account by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var editName by remember { mutableStateOf(false) }
    var avatarMenu by remember { mutableStateOf(false) }
    var cancelStep by remember { mutableStateOf(CancelStep.NONE) }
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.setAvatar(context.applicationContext, uri)
    }

    val profileRows = listOf(
        SettingRow.Link(R.drawable.ic_badge, stringResource(R.string.account_name), summary = account.fullName) { editName = true },
        SettingRow.Link(R.drawable.ic_mail, stringResource(R.string.account_email), summary = account.email) { nav.navigate(ChangeEmail) },
    )
    val planRows = buildList {
        add(SettingRow.Info(R.drawable.ic_workspace_premium, stringResource(R.string.account_plan), stringResource(planName(account.proLevel))))
        if (account.proLevel != MegaAccountDetails.ACCOUNT_TYPE_FREE) {
            val date = if (account.subscriptionValid && account.renewTime > 0) account.renewTime else account.proExpiration
            if (date > 0) {
                add(
                    SettingRow.Info(
                        R.drawable.ic_schedule,
                        stringResource(if (account.subscriptionValid) R.string.account_renews else R.string.account_expires),
                        Format.dateTime(date),
                    ),
                )
            }
        }
        add(SettingRow.Link(R.drawable.ic_open_in_new, stringResource(R.string.account_upgrade), summary = stringResource(R.string.account_upgrade_summary)) {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRICING_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
    }
    val achievementRows = if (account.achievementsStorage > 0 || account.achievementsTransfer > 0) listOf(
        SettingRow.Info(
            R.drawable.ic_emoji_events,
            stringResource(R.string.account_achievements),
            stringResource(R.string.account_achievements_value, Format.size(account.achievementsStorage), Format.size(account.achievementsTransfer)),
        ),
    ) else emptyList()
    val dangerRows = listOf(
        SettingRow.Link(R.drawable.ic_delete_forever, stringResource(R.string.account_cancel), destructive = true) {
            cancelStep = CancelStep.WARN
        },
    )
    val profileTitle = stringResource(R.string.account_profile)
    val planTitle = stringResource(R.string.account_plan_title)
    val dangerTitle = stringResource(R.string.account_danger)

    SettingsScaffold(title = stringResource(R.string.account_title)) {
        item(key = "hero") {
            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box {
                    Avatar(user = account.userHandle, name = account.fullName, size = 112.dp, version = account.avatarVersion)
                    Box(Modifier.align(Alignment.BottomEnd)) {
                        SmallFloatingActionButton(onClick = { avatarMenu = true }) {
                            if (busy) LoadingIndicator(Modifier.size(20.dp)) else Ic(R.drawable.ic_photo_camera, stringResource(R.string.account_change_photo))
                        }
                        DropdownMenu(expanded = avatarMenu, onDismissRequest = { avatarMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.account_change_photo)) },
                                leadingIcon = { Ic(R.drawable.ic_add_photo_alternate) },
                                onClick = {
                                    avatarMenu = false
                                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.account_remove_photo)) },
                                leadingIcon = { Ic(R.drawable.ic_delete) },
                                onClick = {
                                    avatarMenu = false
                                    vm.removeAvatar()
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(account.fullName, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(account.email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        settingsGroup(profileTitle, profileRows)
        item(key = "storage") { StorageCard(account) }
        settingsGroup(planTitle, planRows + achievementRows)
        settingsGroup(dangerTitle, dangerRows)
    }

    if (editName) {
        NameDialog(account.firstName, account.lastName, onSave = vm::setName, onDismiss = { editName = false })
    }
    when (cancelStep) {
        CancelStep.NONE -> Unit
        CancelStep.WARN -> AlertDialog(
            onDismissRequest = { cancelStep = CancelStep.NONE },
            icon = { Ic(R.drawable.ic_warning, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.account_cancel_title)) },
            text = { Text(stringResource(R.string.account_cancel_text)) },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            cancelStep = if (twoFactorEnabled()) CancelStep.CODE else CancelStep.NONE
                            if (cancelStep == CancelStep.NONE) vm.cancelAccount(null) { cancelStep = CancelStep.SENT }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                ) { Text(stringResource(R.string.account_cancel_send)) }
            },
            dismissButton = { TextButton(onClick = { cancelStep = CancelStep.NONE }) { Text(stringResource(R.string.cancel)) } },
        )
        CancelStep.CODE -> {
            var code by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { cancelStep = CancelStep.NONE },
                title = { Text(stringResource(R.string.security_2fa_code_title)) },
                text = { OtpField(code, { code = it }) },
                confirmButton = {
                    Button(onClick = { vm.cancelAccount(code) { cancelStep = CancelStep.SENT } }, enabled = code.length == 6) {
                        Text(stringResource(R.string.account_cancel_send))
                    }
                },
                dismissButton = { TextButton(onClick = { cancelStep = CancelStep.NONE }) { Text(stringResource(R.string.cancel)) } },
            )
        }
        CancelStep.SENT -> AlertDialog(
            onDismissRequest = { cancelStep = CancelStep.NONE },
            icon = { Ic(R.drawable.ic_mail) },
            title = { Text(stringResource(R.string.account_cancel_sent_title)) },
            text = { Text(stringResource(R.string.account_cancel_sent_text, account.email)) },
            confirmButton = { FilledTonalButton(onClick = { cancelStep = CancelStep.NONE }) { Text(stringResource(R.string.ok)) } },
        )
    }
}

private enum class CancelStep { NONE, WARN, CODE, SENT }

private const val PRICING_URL = "https://mega.io/pricing"

@Composable
private fun NameDialog(first: String, last: String, onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var f by remember { mutableStateOf(first) }
    var l by remember { mutableStateOf(last) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.account_name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(f, { f = it }, label = { Text(stringResource(R.string.auth_first_name)) }, singleLine = true)
                OutlinedTextField(l, { l = it }, label = { Text(stringResource(R.string.auth_last_name)) }, singleLine = true)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(f, l)
                    onDismiss()
                },
                enabled = f.isNotBlank() && (f.trim() != first || l.trim() != last),
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Storage use split into Cloud drive, Backups, Rubbish bin, versions, and free space. */
@Composable
private fun StorageCard(account: AccountInfo) {
    val s = account.storage
    SectionHeader(stringResource(R.string.account_storage))
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            if (s == null) {
                Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
                    if (account.error != null) Text(account.error, color = MaterialTheme.colorScheme.error)
                    else LoadingIndicator()
                }
                return@Column
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Format.size(s.used), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.account_of, Format.size(s.max)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            val colors = listOf(
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.tertiary,
                AppTheme.extra.warning,
                MaterialTheme.colorScheme.secondary,
            )
            val segments = listOf(s.cloud - s.versions.coerceAtMost(s.cloud), s.backups, s.rubbish, s.versions)
            SegmentBar(segments.map { if (s.max > 0) it.toFloat() / s.max else 0f }, colors)
            Spacer(Modifier.height(16.dp))
            val labels = listOf(R.string.cloud_drive, R.string.backups, R.string.rubbish_bin, R.string.account_versions)
            labels.forEachIndexed { i, label ->
                LegendRow(colors[i], stringResource(label), Format.size(segments[i].coerceAtLeast(0)))
            }
            LegendRow(MaterialTheme.colorScheme.surfaceContainerHighest, stringResource(R.string.account_free), Format.size((s.max - s.used).coerceAtLeast(0)))
            if (s.transferMax > 0) {
                Spacer(Modifier.height(20.dp))
                Text(stringResource(R.string.account_transfer_quota), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                UsageBar(s.transferUsed.toFloat() / s.transferMax)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.menu_storage_of, Format.size(s.transferUsed), Format.size(s.transferMax)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SegmentBar(fractions: List<Float>, colors: List<Color>) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(Modifier.fillMaxWidth().height(16.dp).clip(MaterialTheme.shapes.small)) {
        drawRoundRect(track, cornerRadius = CornerRadius(8.dp.toPx()))
        val gap = 3.dp.toPx()
        var x = 0f
        fractions.forEachIndexed { i, f ->
            val w = (f.coerceIn(0f, 1f) * size.width)
            if (w >= 1f) {
                drawRoundRect(colors[i], topLeft = Offset(x, 0f), size = Size(w, size.height), cornerRadius = CornerRadius(4.dp.toPx()))
                x += w + gap
            }
        }
    }
}

@Composable
private fun LegendRow(color: Color, label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
