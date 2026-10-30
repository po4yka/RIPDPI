package com.poyka.ripdpi.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.theme.RipDpiSymbolPaths as P

object RipDpiIconSizes {
    val Small = 16.dp
    val Compact = 18.dp
    val Default = 24.dp
    val Medium = 24.dp
    val Large = 32.dp
}

private const val SymbolViewport = 960f

// Icon composables replace the fill with their tint, so the builder color only marks the path as filled.
private fun symbol(
    name: String,
    pathData: String,
    autoMirror: Boolean = false,
): ImageVector =
    ImageVector
        .Builder(
            name = "RipDpiIcons.$name",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = SymbolViewport,
            viewportHeight = SymbolViewport,
            autoMirror = autoMirror,
        ).addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black))
        .build()

// Keep iconography on a single outlined Material Symbols language. Screens should import only this facade.
object RipDpiIcons {
    val Home: ImageVector by lazy { symbol("Home", P.Home) }
    val Config: ImageVector by lazy { symbol("Config", P.Tune) }
    val Logs: ImageVector by lazy { symbol("Logs", P.Article, autoMirror = true) }
    val Settings: ImageVector by lazy { symbol("Settings", P.Settings) }
    val Connected: ImageVector by lazy { symbol("Connected", P.TaskAlt) }
    val Offline: ImageVector by lazy { symbol("Offline", P.Cancel) }
    val Vpn: ImageVector by lazy { symbol("Vpn", P.VpnKey) }
    val Dns: ImageVector by lazy { symbol("Dns", P.Dns) }
    val Advanced: ImageVector by lazy { symbol("Advanced", P.Tune) }
    val Search: ImageVector by lazy { symbol("Search", P.Search) }
    val Warning: ImageVector by lazy { symbol("Warning", P.Warning) }
    val Error: ImageVector by lazy { symbol("Error", P.Cancel) }
    val Info: ImageVector by lazy { symbol("Info", P.Info) }
    val Lock: ImageVector by lazy { symbol("Lock", P.Lock) }
    val LockOpen: ImageVector by lazy { symbol("LockOpen", P.LockOpen) }
    val Copy: ImageVector by lazy { symbol("Copy", P.ContentCopy) }
    val Favorite: ImageVector by lazy { symbol("Favorite", P.Star) }
    val FavoriteFilled: ImageVector by lazy { symbol("FavoriteFilled", P.StarFilled) }
    val Share: ImageVector by lazy { symbol("Share", P.Share) }
    val ChevronRight: ImageVector by lazy { symbol("ChevronRight", P.KeyboardArrowRight, autoMirror = true) }
    val Back: ImageVector by lazy { symbol("Back", P.ArrowBack, autoMirror = true) }
    val Overflow: ImageVector by lazy { symbol("Overflow", P.MoreVert) }
    val Close: ImageVector by lazy { symbol("Close", P.Close) }
    val Check: ImageVector by lazy { symbol("Check", P.Check) }
    val KeyboardArrowDown: ImageVector by lazy { symbol("KeyboardArrowDown", P.KeyboardArrowDown) }
    val KeyboardArrowUp: ImageVector by lazy { symbol("KeyboardArrowUp", P.KeyboardArrowUp) }
    val LocationOn: ImageVector by lazy { symbol("LocationOn", P.LocationOn) }
    val NetworkCheck: ImageVector by lazy { symbol("NetworkCheck", P.NetworkCheck) }
    val Public: ImageVector by lazy { symbol("Public", P.Public) }
    val Remove: ImageVector by lazy { symbol("Remove", P.Remove) }
    val Shield: ImageVector by lazy { symbol("Shield", P.Shield) }
    val Timer: ImageVector by lazy { symbol("Timer", P.Timer) }
    val Videocam: ImageVector by lazy { symbol("Videocam", P.Videocam) }
    val Visibility: ImageVector by lazy { symbol("Visibility", P.Visibility) }
    val QrCodeScanner: ImageVector by lazy { symbol("QrCodeScanner", P.QrCodeScanner) }
    val PhotoCamera: ImageVector by lazy { symbol("PhotoCamera", P.PhotoCamera) }
    val Image: ImageVector by lazy { symbol("Image", P.Image) }
    val Refresh: ImageVector by lazy { symbol("Refresh", P.Refresh) }
    val Archive: ImageVector by lazy { symbol("Archive", P.Archive) }
    val Delete: ImageVector by lazy { symbol("Delete", P.Delete) }
    val Add: ImageVector by lazy { symbol("Add", P.Add) }
    val Paste: ImageVector by lazy { symbol("Paste", P.ContentPaste) }
    val Import: ImageVector by lazy { symbol("Import", P.FileOpen) }
    val OpenExternal: ImageVector by lazy { symbol("OpenExternal", P.OpenInNew, autoMirror = true) }
    val MoveTo: ImageVector by lazy { symbol("MoveTo", P.DriveFileMove, autoMirror = true) }
    val Restore: ImageVector by lazy { symbol("Restore", P.SettingsBackupRestore) }
    val History: ImageVector by lazy { symbol("History", P.History) }
    val Edit: ImageVector by lazy { symbol("Edit", P.Edit) }
    val Tuner: ImageVector by lazy { symbol("Tuner", P.Science) }
    val CameraOff: ImageVector by lazy { symbol("CameraOff", P.NoPhotography) }
}
