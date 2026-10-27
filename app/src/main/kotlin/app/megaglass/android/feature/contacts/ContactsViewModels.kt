package app.megaglass.android.feature.contacts

import app.megaglass.android.core.util.catching
import app.megaglass.android.core.util.userMessage
import app.megaglass.android.core.util.whileObserved

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.core.mega.MegaException
import app.megaglass.android.core.mega.NodeItem
import app.megaglass.android.core.mega.await
import app.megaglass.android.core.mega.toItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nz.mega.sdk.MegaApiJava
import nz.mega.sdk.MegaContactRequest
import nz.mega.sdk.MegaError
import nz.mega.sdk.MegaUser

@Immutable
data class ContactRequestItem(
    val handle: Long,
    val email: String,
    val message: String?,
    /** Seconds since epoch. */
    val created: Long,
    val outgoing: Boolean,
    val status: Int,
)

private fun MegaContactRequest.toItem() = ContactRequestItem(
    handle = handle,
    email = (if (isOutgoing) targetEmail else sourceEmail).orEmpty(),
    message = sourceMessage?.takeIf { it.isNotBlank() },
    created = creationTime,
    outgoing = isOutgoing,
    status = status,
)

/** User text for contact errors. */
internal fun contactError(e: Throwable): String {
    val res = MegaGlassApp.graph.context.resources
    if (e !is MegaException) return res.userMessage(e)
    return when (e.code) {
        MegaError.API_EEXIST -> res.getString(R.string.contact_error_exists)
        MegaError.API_EARGS -> res.getString(R.string.contact_error_args)
        MegaError.API_EACCESS -> res.getString(R.string.contact_error_access)
        else -> res.userMessage(e)
    }
}

data class ContactsState(
    val contacts: List<Contact>? = null,
    val incomingRequests: Int = 0,
    val error: String? = null,
)

class ContactsViewModel : ViewModel() {

    private val graph = MegaGlassApp.graph
    private val api: MegaApiJava get() = graph.mega.api
    private val directory = ContactDirectory.instance
    private val _state = MutableStateFlow(ContactsState())
    val state: StateFlow<ContactsState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch { directory.changes.debounce(200).collect { load() } }
    }

    fun load() = viewModelScope.launch {
        catching {
            val contacts = directory.contacts()
            val incoming = withContext(Dispatchers.IO) {
                api.incomingContactRequests.count { it.status == MegaContactRequest.STATUS_UNRESOLVED }
            }
            contacts to incoming
        }.onSuccess { (contacts, incoming) ->
            _state.update { it.copy(contacts = contacts, incomingRequests = incoming, error = null) }
        }.onFailure { e ->
            _state.update { it.copy(contacts = it.contacts ?: emptyList(), error = contactError(e)) }
        }
    }

    fun invite(email: String, message: String) = viewModelScope.launch {
        catching {
            api.await { inviteContact(email.trim(), message.ifBlank { null }, MegaContactRequest.INVITE_ACTION_ADD, it) }
        }.onSuccess {
            graph.messenger.show(graph.context.getString(R.string.contact_invite_sent, email.trim()))
        }.onFailure { graph.messenger.show(contactError(it)) }
    }
}

data class RequestsState(
    val received: List<ContactRequestItem>? = null,
    val sent: List<ContactRequestItem>? = null,
    val busy: Set<Long> = emptySet(),
)

class ContactRequestsViewModel : ViewModel() {

    private val graph = MegaGlassApp.graph
    private val api: MegaApiJava get() = graph.mega.api
    private val _state = MutableStateFlow(RequestsState())
    val state: StateFlow<RequestsState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch { merge(graph.mega.contactRequests, ContactDirectory.instance.changes).debounce(150).collect { load() } }
    }

    fun load() = viewModelScope.launch {
        val (received, sent) = withContext(Dispatchers.IO) {
            api.incomingContactRequests
                .filter { it.status == MegaContactRequest.STATUS_UNRESOLVED || it.status == MegaContactRequest.STATUS_REMINDED }
                .map { it.toItem() }
                .sortedByDescending { it.created } to
                api.outgoingContactRequests
                    .filter { it.status == MegaContactRequest.STATUS_UNRESOLVED || it.status == MegaContactRequest.STATUS_REMINDED }
                    .map { it.toItem() }
                    .sortedByDescending { it.created }
        }
        _state.update { it.copy(received = received, sent = sent) }
    }

    /** [action] is MegaContactRequest.REPLY_ACTION_*. */
    fun reply(item: ContactRequestItem, action: Int) = run(item) {
        val request = api.getContactRequestByHandle(item.handle) ?: return@run
        api.await { replyContactRequest(request, action, it) }
        val text = when (action) {
            MegaContactRequest.REPLY_ACTION_ACCEPT -> R.string.contact_request_accepted
            MegaContactRequest.REPLY_ACTION_DENY -> R.string.contact_request_denied
            else -> R.string.contact_request_ignored
        }
        graph.messenger.show(graph.context.getString(text))
    }

    fun remind(item: ContactRequestItem) = run(item) {
        api.await { inviteContact(item.email, null, MegaContactRequest.INVITE_ACTION_REMIND, it) }
        graph.messenger.show(graph.context.getString(R.string.contact_reminder_sent))
    }

    fun cancel(item: ContactRequestItem) = run(item) {
        api.await { inviteContact(item.email, null, MegaContactRequest.INVITE_ACTION_DELETE, it) }
        graph.messenger.show(graph.context.getString(R.string.contact_invite_cancelled))
    }

    private fun run(item: ContactRequestItem, block: suspend () -> Unit) {
        if (item.handle in _state.value.busy) return
        _state.update { it.copy(busy = it.busy + item.handle) }
        viewModelScope.launch {
            catching { withContext(Dispatchers.IO) { block() } }.onFailure { graph.messenger.show(contactError(it)) }
            _state.update { it.copy(busy = it.busy - item.handle) }
            load()
        }
    }
}

data class ContactDetailState(
    val loading: Boolean = true,
    val contact: Contact? = null,
    val shares: List<NodeItem> = emptyList(),
    /** Hex credentials split in groups of four characters. */
    val myCredentials: List<String> = emptyList(),
    val theirCredentials: List<String> = emptyList(),
    val removed: Boolean = false,
    val busy: Boolean = false,
)

class ContactDetailViewModel(private val email: String) : ViewModel() {

    private val graph = MegaGlassApp.graph
    private val api: MegaApiJava get() = graph.mega.api
    private val directory = ContactDirectory.instance
    private val _state = MutableStateFlow(ContactDetailState())
    val state: StateFlow<ContactDetailState> = _state.asStateFlow()
    private var loadJob: Job? = null

    init {
        load()
        // Reload only while the screen shows: each node change in the account sends a value (MEGAGLASS-PERF-010).
        viewModelScope.launch { merge(directory.changes, graph.mega.nodes).whileObserved(_state.subscriptionCount).debounce(200).collect { load() } }
    }

    private fun groups(hex: String?): List<String> = hex.orEmpty().uppercase().chunked(4)

    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch { loadNow() }
    }

    private suspend fun loadNow() {
        // getContact also returns people who are no longer contacts.
        val user = withContext(Dispatchers.IO) { api.getContact(email)?.takeIf { it.visibility == MegaUser.VISIBILITY_VISIBLE } }
        if (user == null) {
            _state.update { it.copy(loading = false, contact = null) }
            return
        }
        val contact = directory.toContact(user)
        val shares = withContext(Dispatchers.IO) { api.getInShares(user).map { it.toItem(api, owner = contact.name) } }
        val mine = withContext(Dispatchers.IO) { api.myCredentials }
        val theirs = catching { api.await { getUserCredentials(user, it) }.password }.getOrNull()
        _state.update {
            it.copy(
                loading = false,
                contact = contact,
                shares = shares,
                myCredentials = groups(mine),
                theirCredentials = groups(theirs),
            )
        }
    }

    fun setAlias(alias: String) = action {
        val c = _state.value.contact ?: return@action
        api.await { setUserAlias(c.handle, alias.trim().ifEmpty { null }, it) }
        directory.forget(c.handle)
    }

    fun setVerified(verified: Boolean) = action {
        val user = api.getContact(email) ?: return@action
        if (verified) api.await { verifyCredentials(user, it) } else api.await { resetCredentials(user, it) }
        _state.value.contact?.let { directory.forget(it.handle) }
        graph.messenger.show(graph.context.getString(if (verified) R.string.contact_verified else R.string.contact_verification_reset))
    }

    fun remove() = action {
        val user = api.getContact(email) ?: return@action
        api.await { removeContact(user, it) }
        graph.messenger.show(graph.context.getString(R.string.contact_removed, email))
        _state.update { it.copy(removed = true) }
    }

    private fun action(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            catching { withContext(Dispatchers.IO) { block() } }.onFailure { graph.messenger.show(contactError(it)) }
            _state.update { it.copy(busy = false) }
            if (!_state.value.removed) load()
        }
    }
}
