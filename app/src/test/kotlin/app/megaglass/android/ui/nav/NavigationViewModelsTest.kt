package app.megaglass.android.ui.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Test

class NavigationViewModelsTest {
    private class AccountState : ViewModel() {
        var cleared = false
        override fun onCleared() { cleared = true }
    }

    @Test fun leavingAnAccountClearsItsViewModelsAndDoesNotReuseThem() {
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(AccountState())!!
        }
        val first = NavigationViewModels()
        val old = ViewModelProvider(first, factory)[AccountState::class.java]
        first.close()
        assertTrue(old.cleared)
        val second = NavigationViewModels()
        val fresh = ViewModelProvider(second, factory)[AccountState::class.java]
        assertNotSame(old, fresh)
        assertFalse(fresh.cleared)
        second.close()
    }
}
