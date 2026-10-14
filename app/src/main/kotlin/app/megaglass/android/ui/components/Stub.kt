package app.megaglass.android.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.megaglass.android.R
import app.megaglass.android.ui.nav.LocalNavigator

/** Placeholder for a screen that is not built yet. */
@Composable
fun StubScreen(title: String) {
    val nav = LocalNavigator.current
    Scaffold(
        topBar = { TopAppBar(title = { Text(title) }, navigationIcon = { BackButton { nav.back() } }) },
    ) { padding ->
        EmptyState(R.drawable.ic_hourglass_empty, title, Modifier.padding(padding))
    }
}
