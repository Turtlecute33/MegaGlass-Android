package app.megaglass.android.feature.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.megaglass.android.R
import app.megaglass.android.core.util.Format
import app.megaglass.android.ui.components.BackButton
import app.megaglass.android.ui.components.EmptyState
import app.megaglass.android.ui.components.LoadingBox
import app.megaglass.android.ui.nav.LocalNavigator
import kotlinx.coroutines.launch
import nz.mega.sdk.MegaContactRequest

@Composable
fun ContactRequestsScreen() {
    val vm: ContactRequestsViewModel = viewModel { ContactRequestsViewModel() }
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val pager = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.contacts_requests)) },
                    navigationIcon = { BackButton { nav.back() } },
                )
                SecondaryTabRow(selectedTabIndex = pager.currentPage) {
                    val received = state.received?.size ?: 0
                    val sent = state.sent?.size ?: 0
                    Tab(
                        selected = pager.currentPage == 0,
                        onClick = { scope.launch { pager.animateScrollToPage(0) } },
                        text = { Text(stringResource(R.string.contacts_received, received)) },
                    )
                    Tab(
                        selected = pager.currentPage == 1,
                        onClick = { scope.launch { pager.animateScrollToPage(1) } },
                        text = { Text(stringResource(R.string.contacts_sent, sent)) },
                    )
                }
            }
        },
    ) { padding ->
        HorizontalPager(pager, Modifier.fillMaxSize().padding(padding)) { page ->
            val list = if (page == 0) state.received else state.sent
            when {
                list == null -> LoadingBox()
                list.isEmpty() -> EmptyState(
                    icon = if (page == 0) R.drawable.ic_inbox else R.drawable.ic_outbox,
                    title = stringResource(if (page == 0) R.string.contacts_no_received else R.string.contacts_no_sent),
                )
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list, key = { it.handle }) { item ->
                        RequestCard(item, busy = item.handle in state.busy, vm = vm, modifier = Modifier.animateItem())
                    }
                }
            }
        }
    }
}

@Composable
private fun RequestCard(item: ContactRequestItem, busy: Boolean, vm: ContactRequestsViewModel, modifier: Modifier = Modifier) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EmailAvatar(item.email, item.email)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.email, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val status = if (item.status == MegaContactRequest.STATUS_REMINDED) {
                        stringResource(R.string.contacts_reminded) + " · "
                    } else {
                        ""
                    }
                    Text(
                        status + Format.date(item.created),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item.message?.takeIf { !item.outgoing }?.let { msg ->
                Spacer(Modifier.padding(top = 12.dp))
                Text("“$msg”", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.padding(top = 12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (item.outgoing) {
                    TextButton(onClick = { vm.cancel(item) }, enabled = !busy) { Text(stringResource(R.string.contacts_cancel_invite)) }
                    OutlinedButton(onClick = { vm.remind(item) }, enabled = !busy) { Text(stringResource(R.string.contacts_remind)) }
                } else {
                    TextButton(onClick = { vm.reply(item, MegaContactRequest.REPLY_ACTION_IGNORE) }, enabled = !busy) {
                        Text(stringResource(R.string.contacts_ignore))
                    }
                    OutlinedButton(onClick = { vm.reply(item, MegaContactRequest.REPLY_ACTION_DENY) }, enabled = !busy) {
                        Text(stringResource(R.string.contacts_deny))
                    }
                    Button(onClick = { vm.reply(item, MegaContactRequest.REPLY_ACTION_ACCEPT) }, enabled = !busy) {
                        Text(stringResource(R.string.contacts_accept))
                    }
                }
            }
        }
    }
}
