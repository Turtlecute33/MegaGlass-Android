package app.megaglass.android.feature.contacts

import app.megaglass.android.core.util.catching

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.core.mega.await
import app.megaglass.android.core.media.UserAvatar
import app.megaglass.android.core.storage.displayName as safeDisplayName
import app.megaglass.android.ui.components.Avatar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import nz.mega.sdk.MegaApiJava
import nz.mega.sdk.MegaUser
import java.util.concurrent.ConcurrentHashMap

/** A MEGA user as the UI shows it. */
@Immutable
data class Contact(
    val handle: Long,
    val email: String,
    /** Alias, else first and last name, else e-mail. */
    val name: String,
    val fullName: String,
    val alias: String?,
    /** Avatar colour that MEGA assigns to the user, as ARGB. 0 when unknown. */
    val color: Int,
    val verified: Boolean,
    /** Seconds since epoch when the contact was added. */
    val since: Long,
    val avatarVersion: Int,
)

/**
 * Display names of contacts. The SDK keeps first and last names as user attributes that need a
 * request each, so they are kept in memory and dropped when the SDK reports a change.
 */
class ContactDirectory private constructor() {

    private val graph = MegaGlassApp.graph
    private val api: MegaApiJava get() = graph.mega.api

    private data class Names(val full: String, val alias: String?)

    private val names = ConcurrentHashMap<Long, Names>()
    private val avatarVersions = ConcurrentHashMap<Long, Int>()

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    /** Contacts, names, avatars or contact requests changed. */
    val changes: SharedFlow<Unit> = _changes

    init {
        graph.scope.launch {
            graph.mega.users.collect { handles ->
                if (handles.isEmpty()) {
                    names.clear()
                } else {
                    handles.forEach { h ->
                        names.remove(h)
                        avatarVersions.merge(h, 1, Int::plus)
                        api.getContact(MegaApiJava.userHandleToBase64(h))?.email?.let { graph.images.invalidate(UserAvatar(it)) }
                    }
                }
                _changes.tryEmit(Unit)
            }
        }
        graph.scope.launch {
            graph.mega.contactRequests.collect { _changes.tryEmit(Unit) }
        }
    }

    /** Visible contacts, sorted by display name. */
    suspend fun contacts(): List<Contact> = withContext(Dispatchers.IO) {
        val users = api.contacts.filter { it.visibility == MegaUser.VISIBILITY_VISIBLE }
        val gate = Semaphore(6)
        coroutineScope { users.map { async { gate.withPermit { toContact(it) } } }.awaitAll() }
            .sortedBy { it.name.lowercase() }
    }

    /** The contact with this e-mail, or null when the user is not a contact. */
    suspend fun contact(email: String): Contact? = withContext(Dispatchers.IO) {
        api.getContact(email)?.let { toContact(it) }
    }

    /** Display name for an e-mail; the e-mail itself for people who are not contacts. */
    suspend fun displayName(email: String?): String {
        if (email.isNullOrBlank()) return ""
        return contact(email)?.name ?: email
    }

    suspend fun toContact(user: MegaUser): Contact = withContext(Dispatchers.IO) {
        val n = names[user.handle] ?: fetchNames(user).also { names[user.handle] = it }
        val email = user.email.orEmpty()
        Contact(
            handle = user.handle,
            email = email,
            name = n.alias ?: n.full.ifBlank { email },
            fullName = n.full,
            alias = n.alias,
            color = parseColor(api.getUserAvatarColor(user)),
            verified = catching { api.areCredentialsVerified(user) }.getOrDefault(false),
            since = user.timestamp,
            avatarVersion = avatarVersions[user.handle] ?: 0,
        )
    }

    private suspend fun fetchNames(user: MegaUser): Names = coroutineScope {
        val first = async { catching { api.await { getUserAttribute(user, MegaApiJava.USER_ATTR_FIRSTNAME, it) }.text }.getOrNull() }
        val last = async { catching { api.await { getUserAttribute(user, MegaApiJava.USER_ATTR_LASTNAME, it) }.text }.getOrNull() }
        val alias = async { catching { api.await { getUserAlias(user.handle, it) }.name }.getOrNull() }
        Names(
            full = safeDisplayName(listOfNotNull(first.await(), last.await()).joinToString(" ").trim()),
            alias = alias.await()?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /** Called after the user changes an alias, so that the next read gets it. */
    fun forget(handle: Long) {
        names.remove(handle)
        _changes.tryEmit(Unit)
    }

    private fun parseColor(value: String?): Int =
        catching { android.graphics.Color.parseColor(value) }.getOrDefault(0)

    companion object {
        private val holder = lazy { ContactDirectory() }
        val instance: ContactDirectory get() = holder.value
        fun clearIfCreated() {
            if (holder.isInitialized()) {
                holder.value.names.clear()
                holder.value.avatarVersions.clear()
            }
        }
    }
}

/** Avatar of a contact, tinted with the colour MEGA gives the user. */
@Composable
fun ContactAvatar(contact: Contact, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val base = MaterialTheme.colorScheme.primaryContainer
    val tint = if (contact.color != 0) lerp(base, Color(contact.color), 0.35f) else base
    Avatar(contact.email, contact.name, modifier, size, contact.avatarVersion, tint)
}

/** Avatar for an e-mail that may not be a contact. */
@Composable
fun EmailAvatar(email: String, name: String, modifier: Modifier = Modifier, size: Dp = 40.dp) =
    Avatar(email, name.ifBlank { email }, modifier, size)
