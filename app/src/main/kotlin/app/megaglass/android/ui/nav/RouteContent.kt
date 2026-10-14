package app.megaglass.android.ui.nav

import androidx.compose.runtime.Composable
import app.megaglass.android.feature.account.AccountScreen
import app.megaglass.android.feature.contacts.ContactDetailScreen
import app.megaglass.android.feature.contacts.ContactRequestsScreen
import app.megaglass.android.feature.contacts.ContactsScreen
import app.megaglass.android.feature.drive.BackupsScreen
import app.megaglass.android.feature.drive.CategoryScreen
import app.megaglass.android.feature.drive.FavouritesScreen
import app.megaglass.android.feature.drive.FolderScreen
import app.megaglass.android.feature.drive.NodeInfoScreen
import app.megaglass.android.feature.drive.RubbishScreen
import app.megaglass.android.feature.drive.VersionsScreen
import app.megaglass.android.feature.home.HomeScreen
import app.megaglass.android.feature.links.PublicLinkScreen
import app.megaglass.android.feature.menu.MenuScreen
import app.megaglass.android.feature.notifications.NotificationsScreen
import app.megaglass.android.feature.offline.OfflineScreen
import app.megaglass.android.feature.photos.AlbumScreen
import app.megaglass.android.feature.photos.CameraUploadsSettingsScreen
import app.megaglass.android.feature.photos.PhotosScreen
import app.megaglass.android.feature.search.SearchScreen
import app.megaglass.android.feature.settings.AboutScreen
import app.megaglass.android.feature.settings.AppearanceSettingsScreen
import app.megaglass.android.feature.settings.ChangeEmailScreen
import app.megaglass.android.feature.settings.ChangePasswordScreen
import app.megaglass.android.feature.settings.PasscodeSetupScreen
import app.megaglass.android.feature.settings.RecoveryKeyScreen
import app.megaglass.android.feature.settings.SecuritySettingsScreen
import app.megaglass.android.feature.settings.SessionsScreen
import app.megaglass.android.feature.settings.SettingsScreen
import app.megaglass.android.feature.settings.StorageSettingsScreen
import app.megaglass.android.feature.settings.TransferSettingsScreen
import app.megaglass.android.feature.settings.TwoFactorSetupScreen
import app.megaglass.android.feature.shared.SharedScreen
import app.megaglass.android.feature.sync.DeviceCentreScreen
import app.megaglass.android.feature.sync.SyncsScreen
import app.megaglass.android.feature.transfers.TransfersScreen
import app.megaglass.android.feature.viewer.AudioPlayerScreen
import app.megaglass.android.feature.viewer.ImageViewerScreen
import app.megaglass.android.feature.viewer.PdfViewerScreen
import app.megaglass.android.feature.viewer.TextEditorScreen
import app.megaglass.android.feature.viewer.VideoPlayerScreen

/** The screen for each route. */
@Composable
fun RouteContent(route: Route) {
    when (route) {
        Home -> HomeScreen()
        is Folder -> FolderScreen(route)
        Photos -> PhotosScreen()
        is Shared -> SharedScreen(route)
        Menu -> MenuScreen()
        is Rubbish -> RubbishScreen(route)
        is Backups -> BackupsScreen(route)
        is Search -> SearchScreen(route)
        is Category -> CategoryScreen(route)
        Favourites -> FavouritesScreen()
        is NodeInfo -> NodeInfoScreen(route)
        is Versions -> VersionsScreen(route)
        Transfers -> TransfersScreen()
        Offline -> OfflineScreen(null)
        is OfflineFolder -> OfflineScreen(route.path)
        is Album -> AlbumScreen(route)
        CameraUploadsSettings -> CameraUploadsSettingsScreen()
        Contacts -> ContactsScreen()
        ContactRequests -> ContactRequestsScreen()
        is ContactDetail -> ContactDetailScreen(route)
        Notifications -> NotificationsScreen()
        is PublicLink -> PublicLinkScreen(route)
        Syncs -> SyncsScreen()
        DeviceCentre -> DeviceCentreScreen()
        Account -> AccountScreen()
        Settings -> SettingsScreen()
        SettingsAppearance -> AppearanceSettingsScreen()
        SettingsSecurity -> SecuritySettingsScreen()
        SettingsTransfers -> TransferSettingsScreen()
        SettingsStorage -> StorageSettingsScreen()
        SettingsAbout -> AboutScreen()
        Sessions -> SessionsScreen()
        ChangePassword -> ChangePasswordScreen()
        ChangeEmail -> ChangeEmailScreen()
        TwoFactorSetup -> TwoFactorSetupScreen()
        RecoveryKey -> RecoveryKeyScreen()
        PasscodeSetup -> PasscodeSetupScreen()
        is ImageViewer -> ImageViewerScreen(route)
        is VideoPlayer -> VideoPlayerScreen(route)
        is AudioPlayer -> AudioPlayerScreen(route)
        is PdfViewer -> PdfViewerScreen(route)
        is TextEditor -> TextEditorScreen(route)
    }
}
