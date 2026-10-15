package app.megaglass.android.feature.account

import app.megaglass.android.core.util.catching

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.core.mega.await
import app.megaglass.android.core.mega.awaitResult
import app.megaglass.android.core.media.UserAvatar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nz.mega.sdk.MegaAccountDetails
import nz.mega.sdk.MegaApiJava
import nz.mega.sdk.MegaError
import nz.mega.sdk.MegaUtilsAndroid
import java.io.File

/** Storage use of the account, from the last account details request. */
@Immutable
data class StorageInfo(
    val used: Long,
    val max: Long,
    val cloud: Long,
    val rubbish: Long,
    val backups: Long,
    val versions: Long,
    val transferUsed: Long,
    val transferMax: Long,
) {
    val fraction: Float get() = if (max > 0) used.toFloat() / max else 0f
}

@Immutable
data class AccountInfo(
    val userHandle: String? = null,
    val email: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val proLevel: Int = MegaAccountDetails.ACCOUNT_TYPE_FREE,
    /** Seconds since epoch; 0 when unknown. */
    val proExpiration: Long = 0,
    val renewTime: Long = 0,
    val subscriptionValid: Boolean = false,
    val storage: StorageInfo? = null,
    val achievementsStorage: Long = 0,
    val achievementsTransfer: Long = 0,
    val avatarVersion: Int = 0,
    val loading: Boolean = true,
    val error: String? = null,
) {
    val fullName: String get() = "$firstName $lastName".trim().ifEmpty { email }
}

@StringRes
fun planName(level: Int): Int = when (level) {
    MegaAccountDetails.ACCOUNT_TYPE_PROI -> R.string.plan_pro_i
    MegaAccountDetails.ACCOUNT_TYPE_PROII -> R.string.plan_pro_ii
    MegaAccountDetails.ACCOUNT_TYPE_PROIII -> R.string.plan_pro_iii
    MegaAccountDetails.ACCOUNT_TYPE_LITE -> R.string.plan_pro_lite
    MegaAccountDetails.ACCOUNT_TYPE_STARTER -> R.string.plan_starter
    MegaAccountDetails.ACCOUNT_TYPE_BASIC -> R.string.plan_basic
    MegaAccountDetails.ACCOUNT_TYPE_ESSENTIAL -> R.string.plan_essential
    MegaAccountDetails.ACCOUNT_TYPE_BUSINESS -> R.string.plan_business
    MegaAccountDetails.ACCOUNT_TYPE_PRO_FLEXI -> R.string.plan_pro_flexi
    else -> R.string.plan_free
}

/**
 * Profile and quota of the logged-in user. Used by the Me tab and the Account screen.
 * Reloads when the SDK reports account or user changes.
 */
class AccountViewModel : ViewModel() {

    private val graph = MegaGlassApp.graph
    private val api: MegaApiJava get() = graph.mega.api

    private val _state = MutableStateFlow(AccountInfo())
    val state: StateFlow<AccountInfo> = _state.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            graph.mega.account.debounce(500).collect { loadDetails() }
        }
        viewModelScope.launch {
            graph.mega.users
                .filter { handles -> handles.isEmpty() || api.myUserHandleBinary in handles }
                .debounce(300)
                .collect {
                    invalidateAvatar()
                    loadNames()
                }
        }
    }

    fun load() {
        viewModelScope.launch { loadNames() }
        viewModelScope.launch { loadDetails() }
    }

    private suspend fun loadNames() {
        val (handle, email, first, last) = withContext(Dispatchers.IO) {
            val first = api.awaitResult { getUserAttribute(MegaApiJava.USER_ATTR_FIRSTNAME, it) }
            val last = api.awaitResult { getUserAttribute(MegaApiJava.USER_ATTR_LASTNAME, it) }
            listOf(
                api.myUserHandle.orEmpty(),
                api.myEmail.orEmpty(),
                if (first.first == MegaError.API_OK) first.second.text.orEmpty() else "",
                if (last.first == MegaError.API_OK) last.second.text.orEmpty() else "",
            )
        }
        _state.update { it.copy(userHandle = handle.ifEmpty { null }, email = email, firstName = first, lastName = last) }
    }

    private suspend fun loadDetails() {
        catching {
            withContext(Dispatchers.IO) {
                val details = api.await { getAccountDetails(it) }.megaAccountDetails
                val root = api.rootNode?.handle ?: MegaApiJava.INVALID_HANDLE
                val rubbish = api.rubbishNode?.handle ?: MegaApiJava.INVALID_HANDLE
                val vault = api.vaultNode?.handle ?: MegaApiJava.INVALID_HANDLE
                val storage = StorageInfo(
                    used = details.storageUsed,
                    max = details.storageMax,
                    cloud = details.getStorageUsed(root),
                    rubbish = details.getStorageUsed(rubbish),
                    backups = details.getStorageUsed(vault),
                    versions = details.versionStorageUsed,
                    transferUsed = details.transferUsed,
                    transferMax = details.transferMax,
                )
                val achievements = if (api.isAchievementsEnabled) {
                    catching { api.await { getAccountAchievements(it) }.megaAchievementsDetails }.getOrNull()
                } else null
                AccountUpdate(
                    proLevel = details.proLevel,
                    proExpiration = details.proExpiration,
                    renewTime = details.subscriptionRenewTime,
                    subscriptionValid = details.subscriptionStatus == MegaAccountDetails.SUBSCRIPTION_STATUS_VALID,
                    storage = storage,
                    achievementsStorage = achievements?.currentStorage() ?: 0,
                    achievementsTransfer = achievements?.currentTransfer() ?: 0,
                )
            }
        }.onSuccess { u ->
            _state.update {
                it.copy(
                    proLevel = u.proLevel,
                    proExpiration = u.proExpiration,
                    renewTime = u.renewTime,
                    subscriptionValid = u.subscriptionValid,
                    storage = u.storage,
                    achievementsStorage = u.achievementsStorage,
                    achievementsTransfer = u.achievementsTransfer,
                    loading = false,
                    error = null,
                )
            }
        }.onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
    }

    private class AccountUpdate(
        val proLevel: Int,
        val proExpiration: Long,
        val renewTime: Long,
        val subscriptionValid: Boolean,
        val storage: StorageInfo,
        val achievementsStorage: Long,
        val achievementsTransfer: Long,
    )

    private fun invalidateAvatar() {
        val handle = api.myUserHandle ?: return
        graph.images.invalidate(UserAvatar(handle))
        _state.update { it.copy(avatarVersion = it.avatarVersion + 1) }
    }

    fun setName(first: String, last: String) = run {
        api.await { setUserAttribute(MegaApiJava.USER_ATTR_FIRSTNAME, first.trim(), it) }
        api.await { setUserAttribute(MegaApiJava.USER_ATTR_LASTNAME, last.trim(), it) }
        _state.update { it.copy(firstName = first.trim(), lastName = last.trim()) }
    }

    /** Crops the picked image to a square avatar on this device, then uploads it. */
    fun setAvatar(context: Context, uri: Uri) = run {
        val dir = File(context.cacheDir, "avatar").apply { mkdirs() }
        val source = File(dir, "source")
        val avatar = File(dir, "avatar.jpg")
        try {
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    source.outputStream().use { input.copyTo(it) }
                } ?: error(context.getString(R.string.avatar_read_failed))
                if (!MegaUtilsAndroid.createAvatar(source, avatar)) error(context.getString(R.string.avatar_read_failed))
            }
            api.await { setAvatar(avatar.absolutePath, it) }
        } finally {
            withContext(Dispatchers.IO) { dir.deleteRecursively() }
        }
        invalidateAvatar()
    }

    fun removeAvatar() = run {
        api.await { setAvatar(null, it) }
        invalidateAvatar()
    }

    /** Sends the account cancellation e-mail. [pin] is needed with two-factor authentication. */
    fun cancelAccount(pin: String?, onSent: () -> Unit) = run {
        if (pin == null) api.await { cancelAccount(it) } else api.await { multiFactorAuthCancelAccount(pin, it) }
        onSent()
    }

    private fun run(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                graph.messenger.show(e.message ?: graph.context.getString(R.string.error_title))
            } finally {
                _busy.value = false
            }
        }
    }
}
