package app.megaglass.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import nz.mega.sdk.MegaApiJava

private val strengthDispatcher = Dispatchers.Default.limitedParallelism(1)

/** Native zxcvbn work must not block typing or publish a score for an older password. */
@Composable
fun rememberPasswordStrength(password: String, api: MegaApiJava): Int {
    var strength by remember(password) { mutableIntStateOf(-1) }
    LaunchedEffect(password) {
        if (password.isNotEmpty()) {
            delay(150)
            strength = withContext(strengthDispatcher) { api.getPasswordStrength(password) }
        }
    }
    return strength
}
