package app.megaglass.android.feature.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.megaglass.android.R
import app.megaglass.android.ui.components.BackButton
import app.megaglass.android.ui.components.EmptyState
import app.megaglass.android.ui.components.Ic
import app.megaglass.android.ui.components.IconAction
import app.megaglass.android.ui.components.LoadingBox
import app.megaglass.android.ui.nav.ContactDetail
import app.megaglass.android.ui.nav.ContactRequests
import app.megaglass.android.ui.nav.LocalNavigator
import app.megaglass.android.ui.theme.AppTheme

@Composable
fun ContactsScreen() {
    val vm: ContactsViewModel = viewModel { ContactsViewModel() }
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var inviting by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val contacts = state.contacts
    val filtered = remember(contacts, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            contacts.orEmpty()
        } else {
            contacts.orEmpty().filter { q in it.name.lowercase() || q in it.email.lowercase() || q in it.fullName.lowercase() }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            if (searching) {
                SearchTopBar(query, { query = it }, onClose = { searching = false; query = "" })
            } else {
                MediumFlexibleTopAppBar(
                    title = { Text(stringResource(R.string.contacts_title)) },
                    subtitle = contacts?.let { { Text(pluralStringResource(R.plurals.contacts_count, it.size, it.size)) } },
                    navigationIcon = { BackButton { nav.back() } },
                    actions = { IconAction(R.drawable.ic_search, stringResource(R.string.search), { searching = true }) },
                    scrollBehavior = scroll,
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text(stringResource(R.string.contacts_add)) },
                icon = { Ic(R.drawable.ic_person_add) },
                onClick = { inviting = true },
                expanded = fabExpanded,
            )
        },
    ) { padding ->
        if (contacts == null) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 104.dp),
        ) {
            if (!searching) {
                item(key = "requests") {
                    ListItem(
                        onClick = { nav.navigate(ContactRequests) },
                        leadingContent = { IconBubble(R.drawable.ic_inbox) },
                        supportingContent = {
                            Text(
                                if (state.incomingRequests > 0) {
                                    pluralStringResource(R.plurals.contacts_pending, state.incomingRequests, state.incomingRequests)
                                } else {
                                    stringResource(R.string.contacts_requests_none)
                                },
                            )
                        },
                        trailingContent = {
                            if (state.incomingRequests > 0) Badge { Text(state.incomingRequests.toString()) }
                            else Ic(R.drawable.ic_chevron_right)
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    ) { Text(stringResource(R.string.contacts_requests)) }
                }
            }
            if (filtered.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        icon = R.drawable.ic_group,
                        title = stringResource(if (query.isBlank()) R.string.contacts_empty else R.string.contacts_no_match),
                        message = if (query.isBlank()) stringResource(R.string.contacts_empty_hint) else null,
                        modifier = Modifier.height(420.dp),
                    )
                }
            } else {
                val grouped = filtered.groupBy { it.name.firstOrNull()?.uppercaseChar()?.takeIf(Char::isLetter) ?: '#' }
                grouped.forEach { (letter, group) ->
                    stickyHeader(key = "h:$letter") {
                        Text(
                            letter.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(horizontal = 24.dp, vertical = 6.dp),
                        )
                    }
                    items(group, key = { it.handle }) { contact ->
                        ContactRow(contact, onClick = { nav.navigate(ContactDetail(contact.email)) }, modifier = Modifier.animateItem())
                    }
                }
            }
        }
    }

    if (inviting) InviteDialog(onInvite = vm::invite, onDismiss = { inviting = false })
}

/** App bar with a search field, used while a list is filtered. */
@Composable
internal fun SearchTopBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        title = {
            TextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text(stringResource(R.string.contacts_search_hint)) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        navigationIcon = { BackButton(onClose) },
        actions = {
            if (query.isNotEmpty()) IconAction(R.drawable.ic_close, stringResource(R.string.close), { onQuery("") })
        },
    )
}

@Composable
internal fun ContactRow(contact: Contact, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ListItem(
        onClick = onClick,
        modifier = modifier,
        leadingContent = { ContactAvatar(contact) },
        supportingContent = if (contact.name != contact.email) {
            { Text(contact.email, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        } else {
            null
        },
        trailingContent = if (contact.verified) {
            { Ic(R.drawable.ic_verified_fill, stringResource(R.string.contact_verified_label), tint = AppTheme.extra.success) }
        } else {
            null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    ) { Text(contact.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
internal fun IconBubble(
    icon: Int,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Box(Modifier.size(40.dp).background(container, CircleShape), contentAlignment = Alignment.Center) {
        Ic(icon, null, Modifier.size(22.dp), content)
    }
}

@Composable
private fun InviteDialog(onInvite: (String, String) -> Unit, onDismiss: () -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }
    val valid = email.trim().let { it.contains('@') && it.contains('.') && !it.contains(' ') }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Ic(R.drawable.ic_person_add) },
        title = { Text(stringResource(R.string.contacts_add)) },
        text = {
            Column {
                Text(stringResource(R.string.contacts_invite_text), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(stringResource(R.string.contacts_email)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentType = ContentType.EmailAddress },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it.take(300) },
                    label = { Text(stringResource(R.string.contacts_message_optional)) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onInvite(email, message); onDismiss() }, enabled = valid) {
                Text(stringResource(R.string.contacts_send_invite))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
