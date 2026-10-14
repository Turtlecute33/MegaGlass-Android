package app.megaglass.android.feature.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.megaglass.android.R
import app.megaglass.android.ui.components.BackButton
import app.megaglass.android.ui.components.Ic
import app.megaglass.android.ui.components.SectionHeader
import app.megaglass.android.ui.nav.LocalNavigator

/** One row of a settings group. */
sealed interface SettingRow {
    val key: String

    data class Link(
        @DrawableRes val icon: Int?,
        val title: String,
        val summary: String? = null,
        val badge: Int = 0,
        val value: String? = null,
        val destructive: Boolean = false,
        val enabled: Boolean = true,
        val onClick: () -> Unit,
    ) : SettingRow {
        override val key get() = "link:$title"
    }

    data class Toggle(
        @DrawableRes val icon: Int?,
        val title: String,
        val summary: String? = null,
        val checked: Boolean,
        val enabled: Boolean = true,
        val onChange: (Boolean) -> Unit,
    ) : SettingRow {
        override val key get() = "toggle:$title"
    }

    data class Info(@DrawableRes val icon: Int?, val title: String, val value: String) : SettingRow {
        override val key get() = "info:$title"
    }
}

/** A titled group of rows drawn as one segmented block. */
fun LazyListScope.settingsGroup(title: String?, rows: List<SettingRow>) {
    if (rows.isEmpty()) return
    if (title != null) item(key = "header:$title", contentType = "header") { SectionHeader(title) }
    itemsIndexed(rows, key = { _, r -> (title ?: "") + r.key }, contentType = { _, r -> r::class }) { i, row ->
        SettingRowView(
            row = row,
            index = i,
            count = rows.size,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = ListItemDefaults.SegmentedGap / 2),
        )
    }
}

@Composable
fun SettingRowView(row: SettingRow, index: Int, count: Int, modifier: Modifier = Modifier) {
    val shapes = ListItemDefaults.segmentedShapes(index, count)
    val leading: (@Composable () -> Unit)? = when (row) {
        is SettingRow.Link -> row.icon?.let {
            { Ic(it, null, tint = if (row.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        is SettingRow.Toggle -> row.icon?.let { { Ic(it, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) } }
        is SettingRow.Info -> row.icon?.let { { Ic(it, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) } }
    }
    when (row) {
        is SettingRow.Link -> SegmentedListItem(
            onClick = row.onClick,
            shapes = shapes,
            modifier = modifier,
            enabled = row.enabled,
            leadingContent = leading,
            supportingContent = row.summary?.let { { Text(it) } },
            trailingContent = when {
                row.badge > 0 -> { { Badge { Text(row.badge.toString()) } } }
                row.value != null -> {
                    {
                        Text(
                            row.value,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                    }
                }
                else -> null
            },
        ) {
            Text(row.title, color = if (row.destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
        }
        is SettingRow.Toggle -> SegmentedListItem(
            onClick = { row.onChange(!row.checked) },
            shapes = shapes,
            modifier = modifier,
            enabled = row.enabled,
            leadingContent = leading,
            supportingContent = row.summary?.let { { Text(it) } },
            trailingContent = { Switch(checked = row.checked, onCheckedChange = null, enabled = row.enabled) },
        ) { Text(row.title) }
        is SettingRow.Info -> SegmentedListItem(
            shapes = shapes,
            modifier = modifier,
            leadingContent = leading,
            supportingContent = { Text(row.value, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        ) { Text(row.title) }
    }
}

/** Page frame for settings: collapsing title, back button, and a lazy list. */
@Composable
fun SettingsScaffold(
    title: String,
    subtitle: String? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    showBack: Boolean = true,
    floatingActionButton: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val nav = LocalNavigator.current
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                subtitle = subtitle?.let { { Text(it) } },
                navigationIcon = { if (showBack) BackButton { nav.back() } },
                actions = actions,
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = floatingActionButton,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 32.dp),
            content = content,
        )
    }
}

/** Page frame for a form or a step flow: collapsing title and scrolling column. */
@Composable
fun FormScaffold(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val nav = LocalNavigator.current
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton { nav.back() } },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
                .navigationBarsPadding(),
            content = content,
        )
    }
}

/** Single choice from a short list, as radio buttons in a dialog. */
@Composable
fun ChoiceDialog(
    title: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEachIndexed { i, label ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .selectable(selected = i == selected, role = Role.RadioButton, onClick = {
                                onSelect(i)
                                onDismiss()
                            })
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = i == selected, onClick = null)
                        Spacer(Modifier.size(12.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Tinted card with an icon and a short explanation. */
@Composable
fun NoticeCard(
    @DrawableRes icon: Int,
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Surface(color = color, contentColor = contentColor, shape = MaterialTheme.shapes.large, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Ic(icon, null, Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Vertical space between form blocks. */
@Composable
fun Gap(height: Int = 16) = Box(Modifier.height(height.dp))
