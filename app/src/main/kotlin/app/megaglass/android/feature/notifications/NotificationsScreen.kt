package app.megaglass.android.feature.notifications

import app.megaglass.android.core.util.catching

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.core.mega.await
import app.megaglass.android.core.mega.toItem
import app.megaglass.android.core.util.Format
import app.megaglass.android.feature.contacts.IconBubble
import app.megaglass.android.ui.components.BackButton
import app.megaglass.android.ui.components.EmptyState
import app.megaglass.android.ui.components.LoadingBox
import app.megaglass.android.ui.components.SectionHeader
import app.megaglass.android.ui.nav.Account
import app.megaglass.android.ui.nav.AppNavigator
import app.megaglass.android.ui.nav.ContactDetail
import app.megaglass.android.ui.nav.ContactRequests
import app.megaglass.android.ui.nav.Contacts
import app.megaglass.android.ui.nav.LocalNavigator
import app.megaglass.android.ui.nav.MediaSource
import app.megaglass.android.ui.nav.NodeInfo
import app.megaglass.android.ui.nav.Shared
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nz.mega.sdk.MegaUser
import nz.mega.sdk.MegaUserAlert

@Immutable
data class AlertItem(
    val id: Long,
    val type: Int,
    val heading: String,
    val title: String,
    /** Seconds since epoch. */
    val time: Long,
    /** Not seen when the screen opened. */
    val isNew: Boolean,
    val nodeHandle: Long,
    val email: String?,
)

/** Alert types that belong to chat and meetings, which this app does not have. */
private val chatTypes = setOf(
    MegaUserAlert.TYPE_SCHEDULEDMEETING_NEW,
    MegaUserAlert.TYPE_SCHEDULEDMEETING_DELETED,
    MegaUserAlert.TYPE_SCHEDULEDMEETING_UPDATED,
)

class NotificationsViewModel : ViewModel() {

    private val graph = MegaGlassApp.graph
    private val api get() = graph.mega.api
    private val _alerts = MutableStateFlow<List<AlertItem>?>(null)
    val alerts: StateFlow<List<AlertItem>?> = _alerts.asStateFlow()

    /** Alerts that were unseen when the screen opened stay marked as new during this visit. */
    private val newIds = HashSet<Long>()

    init {
        viewModelScope.launch {
            load()
            catching { api.await { acknowledgeUserAlerts(it) } }
        }
        viewModelScope.launch { graph.mega.alerts.debounce(300).collect { load() } }
    }

    private suspend fun load() {
        _alerts.value = withContext(Dispatchers.IO) {
            api.userAlerts
                .filter { it.relevant && !it.isRemoved && it.type !in chatTypes }
                .map { a ->
                    if (!a.seen) newIds += a.id
                    AlertItem(
                        id = a.id,
                        type = a.type,
                        heading = a.heading.orEmpty(),
                        title = a.title.orEmpty(),
                        time = a.getTimestamp(0),
                        isNew = a.id in newIds,
                        nodeHandle = a.nodeHandle,
                        email = a.email?.takeIf { it.isNotBlank() },
                    )
                }
                .sortedByDescending { it.time }
        }
    }

    /** Opens what the alert is about. */
    fun open(item: AlertItem, nav: AppNavigator) {
        when (item.type) {
            MegaUserAlert.TYPE_INCOMINGPENDINGCONTACT_REQUEST,
            MegaUserAlert.TYPE_INCOMINGPENDINGCONTACT_REMINDER,
            -> nav.navigate(ContactRequests)
            MegaUserAlert.TYPE_CONTACTCHANGE_CONTACTESTABLISHED,
            MegaUserAlert.TYPE_UPDATEDPENDINGCONTACTINCOMING_ACCEPTED,
            MegaUserAlert.TYPE_UPDATEDPENDINGCONTACTOUTGOING_ACCEPTED,
            -> viewModelScope.launch {
                // getContact also returns people who are no longer contacts.
                val email = item.email?.takeIf { e ->
                    withContext(Dispatchers.IO) { api.getContact(e)?.visibility == MegaUser.VISIBILITY_VISIBLE }
                }
                nav.navigate(email?.let { ContactDetail(it) } ?: Contacts)
            }
            MegaUserAlert.TYPE_NEWSHARE,
            MegaUserAlert.TYPE_NEWSHAREDNODES,
            MegaUserAlert.TYPE_UPDATEDSHAREDNODES,
            -> viewModelScope.launch {
                val node = graph.nodes.node(item.nodeHandle)
                if (node == null) {
                    nav.navigate(Shared(0))
                } else {
                    val ui = withContext(Dispatchers.IO) { node.toItem(api) }
                    nav.openNode(ui, MediaSource.Folder(ui.parentHandle))
                }
            }
            MegaUserAlert.TYPE_TAKEDOWN,
            MegaUserAlert.TYPE_TAKEDOWN_REINSTATED,
            -> viewModelScope.launch {
                if (graph.nodes.node(item.nodeHandle) != null) nav.navigate(NodeInfo(item.nodeHandle))
            }
            MegaUserAlert.TYPE_PAYMENT_SUCCEEDED,
            MegaUserAlert.TYPE_PAYMENT_FAILED,
            MegaUserAlert.TYPE_PAYMENTREMINDER,
            -> nav.navigate(Account)
        }
    }
}

@DrawableRes
private fun iconFor(type: Int): Int = when (type) {
    MegaUserAlert.TYPE_INCOMINGPENDINGCONTACT_REQUEST,
    MegaUserAlert.TYPE_INCOMINGPENDINGCONTACT_REMINDER,
    -> R.drawable.ic_person_add
    MegaUserAlert.TYPE_INCOMINGPENDINGCONTACT_CANCELLED,
    MegaUserAlert.TYPE_UPDATEDPENDINGCONTACTINCOMING_DENIED,
    MegaUserAlert.TYPE_UPDATEDPENDINGCONTACTOUTGOING_DENIED,
    MegaUserAlert.TYPE_UPDATEDPENDINGCONTACTINCOMING_IGNORED,
    MegaUserAlert.TYPE_CONTACTCHANGE_DELETEDYOU,
    MegaUserAlert.TYPE_CONTACTCHANGE_ACCOUNTDELETED,
    -> R.drawable.ic_person_remove
    MegaUserAlert.TYPE_CONTACTCHANGE_BLOCKEDYOU -> R.drawable.ic_block
    MegaUserAlert.TYPE_CONTACTCHANGE_CONTACTESTABLISHED,
    MegaUserAlert.TYPE_UPDATEDPENDINGCONTACTINCOMING_ACCEPTED,
    MegaUserAlert.TYPE_UPDATEDPENDINGCONTACTOUTGOING_ACCEPTED,
    -> R.drawable.ic_group
    MegaUserAlert.TYPE_NEWSHARE -> R.drawable.ic_folder_shared
    MegaUserAlert.TYPE_DELETEDSHARE -> R.drawable.ic_folder_off
    MegaUserAlert.TYPE_NEWSHAREDNODES, MegaUserAlert.TYPE_UPDATEDSHAREDNODES -> R.drawable.ic_cloud_upload
    MegaUserAlert.TYPE_REMOVEDSHAREDNODES -> R.drawable.ic_delete
    MegaUserAlert.TYPE_PAYMENT_SUCCEEDED -> R.drawable.ic_check_circle
    MegaUserAlert.TYPE_PAYMENT_FAILED, MegaUserAlert.TYPE_PAYMENTREMINDER -> R.drawable.ic_warning
    MegaUserAlert.TYPE_TAKEDOWN, MegaUserAlert.TYPE_SET_TAKEDOWN -> R.drawable.ic_block
    MegaUserAlert.TYPE_TAKEDOWN_REINSTATED, MegaUserAlert.TYPE_SET_TAKEDOWN_REINSTATED -> R.drawable.ic_settings_backup_restore
    else -> R.drawable.ic_notifications
}

@Composable
fun NotificationsScreen() {
    val vm: NotificationsViewModel = viewModel { NotificationsViewModel() }
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(R.string.notifications_title)) },
                navigationIcon = { BackButton { nav.back() } },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        val list = alerts
        when {
            list == null -> LoadingBox(Modifier.padding(padding))
            list.isEmpty() -> EmptyState(
                icon = R.drawable.ic_notifications,
                title = stringResource(R.string.notifications_empty),
                message = stringResource(R.string.notifications_empty_hint),
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 32.dp),
            ) {
                val (fresh, earlier) = list.partition { it.isNew }
                if (fresh.isNotEmpty()) {
                    item(key = "hNew") { SectionHeader(stringResource(R.string.notifications_new)) }
                    items(fresh, key = { it.id }) { AlertRow(it) { vm.open(it, nav) } }
                }
                if (earlier.isNotEmpty()) {
                    item(key = "hEarlier") { SectionHeader(stringResource(R.string.notifications_earlier)) }
                    items(earlier, key = { it.id }) { AlertRow(it) { vm.open(it, nav) } }
                }
            }
        }
    }
}

@Composable
private fun AlertRow(item: AlertItem, onClick: () -> Unit) {
    val warning = item.type == MegaUserAlert.TYPE_PAYMENT_FAILED || item.type == MegaUserAlert.TYPE_TAKEDOWN ||
        item.type == MegaUserAlert.TYPE_SET_TAKEDOWN || item.type == MegaUserAlert.TYPE_CONTACTCHANGE_BLOCKEDYOU
    ListItem(
        onClick = onClick,
        leadingContent = {
            Box {
                if (warning) {
                    IconBubble(iconFor(item.type), MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                } else {
                    IconBubble(iconFor(item.type))
                }
                if (item.isNew) {
                    Box(
                        Modifier.align(Alignment.TopEnd).size(10.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                    )
                }
            }
        },
        overlineContent = item.heading.takeIf { it.isNotBlank() && it != item.title }?.let { { Text(it) } },
        supportingContent = { Text(Format.date(item.time)) },
        colors = ListItemDefaults.colors(
            containerColor = if (item.isNew) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent,
        ),
    ) {
        Text(item.title.ifBlank { item.heading }, maxLines = 3, color = if (warning) MaterialTheme.colorScheme.error else Color.Unspecified)
    }
}
