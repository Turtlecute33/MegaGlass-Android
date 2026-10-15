package app.megaglass.android.ui.nav

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/** Navigation entries belong to the current ready account, rather than the whole Activity. */
class NavigationViewModels : ViewModelStoreOwner, AutoCloseable {
    override val viewModelStore = ViewModelStore()
    override fun close() = viewModelStore.clear()
}
