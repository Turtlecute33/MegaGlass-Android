package app.megaglass.android.core.storage

import app.megaglass.android.core.util.catching

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.folderSortStore by preferencesDataStore(name = "folder_sort")

/**
 * The sort order of each cloud folder. A folder that the user did not sort uses [DEFAULT].
 * Stored on this device only, and deleted at logout.
 */
class FolderSorts(context: Context) {

    private val store = context.folderSortStore

    private fun key(handle: Long) = stringPreferencesKey(handle.toString())

    /** The order of the folder [handle], and its changes. */
    fun flow(handle: Long): Flow<SortOrder> = store.data
        .map { prefs -> prefs[key(handle)]?.let { catching { SortOrder.valueOf(it) }.getOrNull() } ?: DEFAULT }
        .distinctUntilChanged()

    suspend fun order(handle: Long): SortOrder = flow(handle).first()

    suspend fun set(handle: Long, order: SortOrder) {
        store.edit { it[key(handle)] = order.name }
    }

    suspend fun clear() {
        store.edit { it.clear() }
    }

    companion object {
        /** Largest first. */
        val DEFAULT = SortOrder.SIZE_DESC
    }
}
