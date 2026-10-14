package app.megaglass.android.feature.shared

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.core.mega.MegaException
import app.megaglass.android.core.mega.NodeItem
import app.megaglass.android.core.mega.await
import app.megaglass.android.core.mega.toItem
import app.megaglass.android.feature.contacts.Contact
import app.megaglass.android.feature.contacts.ContactAvatar
import app.megaglass.android.feature.contacts.ContactDirectory
import app.megaglass.android.feature.contacts.EmailAvatar
import app.megaglass.android.ui.components.Ic
import app.megaglass.android.ui.components.NodeThumbnail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nz.mega.sdk.MegaError
import nz.mega.sdk.MegaShare

/** One person who has access to the folder. */
@Immutable
data class ShareMember(
    val email: String,
    val name: String,
    val access: Int,
    val pending: Boolean,
    val verified: Boolean,
    val contact: Contact?,
)

@Stable
private class ShareFolderModel(private val handle: Long, private val scope: CoroutineScope) {
    private val graph = MegaGlassApp.graph
    private val api get() = graph.mega.api
    private val directory = ContactDirectory.instance

    var folder by mutableStateOf<NodeItem?>(null)
    var members by mutableStateOf<List<ShareMember>?>(null)
    var contacts by mutableStateOf<List<Contact>>(emptyList())
    var busy by mutableStateOf(false)

    fun load() = scope.launch {
        val node = withContext(Dispatchers.IO) { api.getNodeByHandle(handle) } ?: return@launch
        folder = withContext(Dispatchers.IO) { node.toItem(api) }
        val shares = withContext(Dispatchers.IO) { api.getOutShares(node) }
        val warn = withContext(Dispatchers.IO) { api.contactVerificationWarningEnabled() }
        members = shares.filter { !it.user.isNullOrBlank() }.map { s ->
            val c = directory.contact(s.user)
            ShareMember(
                email = s.user,
                name = c?.name ?: s.user,
                access = s.access,
                pending = s.isPending,
                verified = s.isVerified || !warn,
                contact = c,
            )
        }.sortedBy { it.name.lowercase() }
        contacts = directory.contacts()
    }

    private fun error(e: Throwable): String {
        val res = graph.context.resources
        return when ((e as? MegaException)?.code) {
            MegaError.API_EACCESS -> res.getString(R.string.share_error_access)
            MegaError.API_EARGS -> res.getString(R.string.share_error_args)
            else -> e.message ?: res.getString(R.string.error_title)
        }
    }

    fun setAccess(member: ShareMember, access: Int) = scope.launch {
        busy = true
        runCatching {
            val node = api.getNodeByHandle(handle) ?: return@runCatching
            api.await { share(node, member.email, access, it) }
        }.onFailure { graph.messenger.show(error(it)) }
        busy = false
        load()
    }

    /** Opens the share keys, then shares with each e-mail. Returns true when all succeed. */
    suspend fun share(emails: List<String>, access: Int): Boolean {
        busy = true
        val result = runCatching {
            val node = withContext(Dispatchers.IO) { api.getNodeByHandle(handle) } ?: return@runCatching
            api.await { openShareDialog(node, it) }
            coroutineScope {
                emails.map { email -> async { api.await { share(node, email, access, it) } } }.awaitAll()
            }
        }
        busy = false
        result.onSuccess {
            graph.messenger.show(graph.context.resources.getQuantityString(R.plurals.share_done, emails.size, emails.size))
        }.onFailure { graph.messenger.show(error(it)) }
        load()
        return result.isSuccess
    }
}

/** Shares a folder with contacts: add people, change access, stop sharing. */
@Composable
fun ShareFolderSheet(handle: Long, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val model = remember(handle) { ShareFolderModel(handle, scope) }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(handle) { model.load() }
    LaunchedEffect(model.members) { if (model.members?.isEmpty() == true) adding = true }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        Column(Modifier.navigationBarsPadding().imePadding()) {
            model.folder?.let { folder ->
                Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    NodeThumbnail(folder, size = 48.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.share_folder_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(folder.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    }
                    if (model.busy) LoadingIndicator(Modifier.size(32.dp))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            AnimatedContent(adding, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "shareMode") { add ->
                if (add) {
                    AddPeople(
                        model = model,
                        onCancel = { if (model.members.isNullOrEmpty()) onDismiss() else adding = false },
                        onShared = { adding = false },
                    )
                } else {
                    Members(model, onAdd = { adding = true })
                }
            }
        }
    }
}

@Composable
private fun Members(model: ShareFolderModel, onAdd: () -> Unit) {
    val members = model.members
    Column {
        if (members == null) {
            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
            return@Column
        }
        Text(
            pluralStringResource(R.plurals.share_people_access, members.size, members.size),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(members, key = { it.email }) { member ->
                MemberRow(member, enabled = !model.busy, onAccess = { model.setAccess(member, it) })
            }
        }
        FilledTonalButton(
            onClick = onAdd,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Ic(R.drawable.ic_person_add)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.share_add_people))
        }
    }
}

@Composable
private fun MemberRow(member: ShareMember, enabled: Boolean, onAccess: (Int) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        leadingContent = {
            if (member.contact != null) ContactAvatar(member.contact) else EmailAvatar(member.email, member.name)
        },
        supportingContent = {
            val parts = buildList {
                if (member.name != member.email) add(member.email)
                if (member.pending) add(stringResource(R.string.share_pending))
                if (!member.verified && !member.pending) add(stringResource(R.string.share_not_verified))
            }
            if (parts.isNotEmpty()) Text(parts.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            Box {
                TextButton(onClick = { menu = true }, enabled = enabled) {
                    Text(stringResource(accessLabel(member.access)))
                    Ic(R.drawable.ic_expand_more, null, Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    listOf(MegaShare.ACCESS_READ, MegaShare.ACCESS_READWRITE, MegaShare.ACCESS_FULL).forEach { level ->
                        DropdownMenuItem(
                            text = { Text(stringResource(accessLabel(level))) },
                            trailingIcon = if (member.access == level) { { Ic(R.drawable.ic_check) } } else null,
                            onClick = {
                                menu = false
                                if (level != member.access) onAccess(level)
                            },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.share_remove_access), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Ic(R.drawable.ic_person_remove, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menu = false
                            onAccess(MegaShare.ACCESS_UNKNOWN)
                        },
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    ) { Text(member.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

private val emailPattern = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

@Composable
private fun AddPeople(model: ShareFolderModel, onCancel: () -> Unit, onShared: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var recipients by remember { mutableStateOf(listOf<String>()) }
    var access by remember { mutableIntStateOf(MegaShare.ACCESS_READ) }
    val existing = model.members.orEmpty().map { it.email.lowercase() }.toSet()
    val suggestions = remember(query, model.contacts, recipients, existing) {
        val q = query.trim().lowercase()
        model.contacts
            .filter { it.email.lowercase() !in existing && it.email !in recipients }
            .filter { q.isEmpty() || q in it.name.lowercase() || q in it.email.lowercase() }
            .take(30)
    }
    val addTyped = {
        val e = query.trim().trimEnd(',', ';')
        if (emailPattern.matches(e) && e.lowercase() !in existing && e !in recipients) {
            recipients = recipients + e
            query = ""
        }
    }

    Column(Modifier.padding(horizontal = 20.dp)) {
        if (recipients.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                recipients.forEach { email ->
                    val contact = model.contacts.firstOrNull { it.email == email }
                    InputChip(
                        selected = true,
                        onClick = { recipients = recipients - email },
                        label = { Text(contact?.name ?: email, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = { Ic(R.drawable.ic_close, stringResource(R.string.close), Modifier.size(18.dp)) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(
            value = query,
            onValueChange = { v ->
                if (v.endsWith(",") || v.endsWith(" ") || v.endsWith(";")) {
                    query = v
                    addTyped()
                } else {
                    query = v
                }
            },
            label = { Text(stringResource(R.string.share_email_or_name)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { addTyped() }),
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.EmailAddress },
        )
        LazyColumn(Modifier.heightIn(max = 260.dp)) {
            if (emailPattern.matches(query.trim()) && suggestions.none { it.email.equals(query.trim(), true) }) {
                item(key = "typed") {
                    ListItem(
                        onClick = addTyped,
                        leadingContent = { EmailAvatar(query.trim(), query.trim()) },
                        supportingContent = { Text(stringResource(R.string.share_invite_new)) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    ) { Text(query.trim()) }
                }
            }
            items(suggestions, key = { it.handle }) { c ->
                ListItem(
                    onClick = {
                        recipients = recipients + c.email
                        query = ""
                    },
                    leadingContent = { ContactAvatar(c) },
                    supportingContent = if (c.name != c.email) { { Text(c.email, maxLines = 1) } } else null,
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                ) { Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.share_access_level), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        val levels = listOf(MegaShare.ACCESS_READ, MegaShare.ACCESS_READWRITE, MegaShare.ACCESS_FULL)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            levels.forEachIndexed { i, level ->
                SegmentedButton(
                    selected = access == level,
                    onClick = { access = level },
                    shape = SegmentedButtonDefaults.itemShape(i, levels.size),
                ) { Text(stringResource(accessShortLabel(level)), maxLines = 1) }
            }
        }
        Text(
            stringResource(accessHelp(access)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            Button(
                onClick = {
                    val emails = recipients
                    scope.launch { if (model.share(emails, access)) onShared() }
                },
                enabled = recipients.isNotEmpty() && !model.busy,
            ) { Text(stringResource(R.string.share_action)) }
        }
    }
}

private fun accessShortLabel(level: Int): Int = when (level) {
    MegaShare.ACCESS_FULL -> R.string.share_access_full_short
    MegaShare.ACCESS_READWRITE -> R.string.share_access_readwrite_short
    else -> R.string.share_access_read_short
}

private fun accessHelp(level: Int): Int = when (level) {
    MegaShare.ACCESS_FULL -> R.string.share_access_full_help
    MegaShare.ACCESS_READWRITE -> R.string.share_access_readwrite_help
    else -> R.string.share_access_read_help
}
