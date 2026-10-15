package app.megaglass.android.feature.contacts

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.ui.components.BackButton
import app.megaglass.android.ui.components.ConfirmDialog
import app.megaglass.android.ui.components.EmptyState
import app.megaglass.android.ui.components.Ic
import app.megaglass.android.ui.components.IconAction
import app.megaglass.android.ui.components.LoadingBox
import app.megaglass.android.ui.components.NodeRow
import app.megaglass.android.ui.components.SectionHeader
import app.megaglass.android.ui.components.TextInputDialog
import app.megaglass.android.ui.nav.ContactDetail
import app.megaglass.android.ui.nav.LocalNavigator
import app.megaglass.android.ui.nav.MediaSource
import app.megaglass.android.ui.theme.AppTheme

@Composable
fun ContactDetailScreen(route: ContactDetail) {
    val vm: ContactDetailViewModel = viewModel(key = route.email) { ContactDetailViewModel(route.email) }
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val resources = LocalResources.current
    var menu by remember { mutableStateOf(false) }
    var editAlias by rememberSaveable { mutableStateOf(false) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    var credentials by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.removed) { if (state.removed) nav.backFrom(route) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { BackButton { nav.back() } },
                actions = {
                    if (state.contact != null) {
                        Box {
                            IconAction(R.drawable.ic_more_vert, stringResource(R.string.more), { menu = true })
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.contact_edit_alias)) },
                                    leadingIcon = { Ic(R.drawable.ic_edit) },
                                    onClick = { menu = false; editAlias = true },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.contact_remove), color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Ic(R.drawable.ic_person_remove, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { menu = false; confirmRemove = true },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        val contact = state.contact
        when {
            state.loading -> LoadingBox(Modifier.padding(padding))
            contact == null -> EmptyState(R.drawable.ic_person, stringResource(R.string.contact_not_found), Modifier.padding(padding))
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 32.dp)) {
                item(key = "header") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        ContactAvatar(contact, size = 112.dp)
                        Spacer(Modifier.height(16.dp))
                        Text(contact.name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        if (contact.alias != null && contact.fullName.isNotBlank()) {
                            Text(contact.fullName, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = {
                            context.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("e-mail", contact.email))
                            MegaGlassApp.graph.messenger.show(resources.getString(R.string.copied))
                        }) {
                            Text(contact.email)
                            Spacer(Modifier.width(6.dp))
                            Ic(R.drawable.ic_content_copy, stringResource(R.string.copied), Modifier.size(16.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        AssistChip(
                            onClick = { credentials = true },
                            label = {
                                Text(stringResource(if (contact.verified) R.string.contact_verified_label else R.string.contact_not_verified))
                            },
                            leadingIcon = {
                                Ic(
                                    if (contact.verified) R.drawable.ic_verified_user else R.drawable.ic_shield,
                                    null,
                                    Modifier.size(AssistChipDefaults.IconSize),
                                    if (contact.verified) AppTheme.extra.success else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                }
                item(key = "sharedHeader") { SectionHeader(stringResource(R.string.contact_shared_folders)) }
                if (state.shares.isEmpty()) {
                    item(key = "noShares") {
                        Text(
                            stringResource(R.string.contact_no_shared_folders),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                } else {
                    items(state.shares, key = { it.handle }) { item ->
                        NodeRow(item = item, onClick = { nav.openNode(item, MediaSource.Folder(item.parentHandle)) })
                    }
                }
                item(key = "remove") {
                    OutlinedButton(
                        onClick = { confirmRemove = true },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
                    ) {
                        Ic(R.drawable.ic_person_remove, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.contact_remove), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    val contact = state.contact
    if (editAlias && contact != null) {
        TextInputDialog(
            title = stringResource(R.string.contact_edit_alias),
            label = stringResource(R.string.contact_alias),
            confirmLabel = stringResource(R.string.save),
            initial = contact.alias.orEmpty(),
            onConfirm = vm::setAlias,
            onDismiss = { editAlias = false },
            validate = { null },
        )
    }
    if (confirmRemove && contact != null) {
        ConfirmDialog(
            title = stringResource(R.string.contact_remove_title, contact.name),
            text = stringResource(R.string.contact_remove_text),
            confirmLabel = stringResource(R.string.contact_remove),
            destructive = true,
            icon = R.drawable.ic_person_remove,
            onConfirm = vm::remove,
            onDismiss = { confirmRemove = false },
        )
    }
    if (credentials && contact != null) {
        CredentialsDialog(
            name = contact.name,
            verified = contact.verified,
            mine = state.myCredentials,
            theirs = state.theirCredentials,
            onVerify = { vm.setVerified(!contact.verified) },
            onDismiss = { credentials = false },
        )
    }
}

@Composable
private fun CredentialsDialog(
    name: String,
    verified: Boolean,
    mine: List<String>,
    theirs: List<String>,
    onVerify: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Ic(R.drawable.ic_verified_user) },
        title = { Text(stringResource(R.string.contact_credentials_title)) },
        text = {
            Column {
                Text(stringResource(R.string.contact_credentials_text), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                Text(name, style = MaterialTheme.typography.labelLarge)
                CredentialGrid(theirs)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.contact_credentials_mine), style = MaterialTheme.typography.labelLarge)
                CredentialGrid(mine)
            }
        },
        confirmButton = {
            Button(onClick = { onVerify(); onDismiss() }, enabled = theirs.isNotEmpty()) {
                Text(stringResource(if (verified) R.string.contact_reset_verification else R.string.contact_mark_verified))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/** Credentials as rows of five groups of four hex characters, as other MEGA apps show them. */
@Composable
private fun CredentialGrid(groups: List<String>) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        if (groups.isEmpty()) {
            Text("…", Modifier.padding(12.dp))
            return@Surface
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            groups.chunked(5).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { g ->
                        Text(g, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                    }
                }
            }
        }
    }
}
