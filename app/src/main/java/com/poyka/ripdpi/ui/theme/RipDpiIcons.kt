package com.poyka.ripdpi.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

object RipDpiIconSizes {
    val Small = 16.dp
    val Default = 20.dp
    val Medium = 24.dp
    val Large = 32.dp
}

private fun buildStrokedIcon(
    name: String,
    pathData: List<String>,
    strokeWidth: Float,
): ImageVector =
    ImageVector
        .Builder(
            name = name,
            defaultWidth = 20.dp,
            defaultHeight = 20.dp,
            viewportWidth = 20f,
            viewportHeight = 20f,
        ).apply {
            pathData.forEach { data ->
                addPath(
                    pathData = PathParser().parsePathString(data).toNodes(),
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = strokeWidth,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                    pathFillType = PathFillType.NonZero,
                )
            }
        }.build()

private val HomeNavigationIcon =
    buildStrokedIcon(
        name = "RipDpiHomeNavigation",
        pathData =
            listOf(
                "M10 18.3333C14.6024 18.3333 18.3333 14.6024 18.3333 10C18.3333 5.39763 14.6024 1.66667 10 1.66667C5.39763 1.66667 1.66667 5.39763 1.66667 10C1.66667 14.6024 5.39763 18.3333 10 18.3333Z",
            ),
        strokeWidth = 1.25f,
    )

private val ConfigNavigationIcon =
    buildStrokedIcon(
        name = "RipDpiConfigNavigation",
        pathData =
            listOf(
                "M3.33333 12.2917V17.5M3.33333 2.5V8.33333M10 10V17.5M10 2.5V6.66667M16.6667 13.3333V17.5M16.6667 2.5V10M1.66667 11.6667H5M8.33333 6.66667H11.6667M15 13.3333H18.3333",
            ),
        strokeWidth = 1.25f,
    )

private val LogsNavigationIcon =
    buildStrokedIcon(
        name = "RipDpiLogsNavigation",
        pathData =
            listOf(
                "M6.66667 17.5H16.6667C17.1087 17.5 17.5326 17.3244 17.8452 17.0118C18.1577 16.6993 18.3333 16.2754 18.3333 15.8333V15C18.3333 14.779 18.2455 14.567 18.0893 14.4107C17.933 14.2545 17.721 14.1667 17.5 14.1667H9.16667C8.94566 14.1667 8.73369 14.2545 8.57741 14.4107C8.42113 14.567 8.33334 14.779 8.33334 15V15.8333C8.33334 16.2754 8.15774 16.6993 7.84518 17.0118C7.53262 17.3244 7.10869 17.5 6.66667 17.5ZM6.66667 17.5C6.22464 17.5 5.80072 17.3244 5.48816 17.0118C5.1756 16.6993 5 16.2754 5 15.8333V4.16667C5 3.72464 4.82441 3.30072 4.51184 2.98816C4.19928 2.67559 3.77536 2.5 3.33334 2.5C2.89131 2.5 2.46739 2.67559 2.15483 2.98816C1.84226 3.30072 1.66667 3.72464 1.66667 4.16667V5.83333C1.66667 6.05435 1.75446 6.26631 1.91074 6.42259C2.06703 6.57887 2.27899 6.66667 2.5 6.66667H5",
                "M15.8333 14.1667V4.16667C15.8333 3.72464 15.6577 3.30072 15.3452 2.98816C15.0326 2.67559 14.6087 2.5 14.1666 2.5H3.3333",
                "M8.33333 6.66667H11.6667M8.33333 10H12.5",
            ),
        strokeWidth = 1.25f,
    )

private val SettingsNavigationIcon =
    buildStrokedIcon(
        name = "RipDpiSettingsNavigation",
        pathData =
            listOf(
                "M10.1833 1.66667H9.81665C9.37462 1.66667 8.9507 1.84226 8.63814 2.15482C8.32558 2.46738 8.14999 2.8913 8.14999 3.33334V3.48334C8.14969 3.7756 8.07253 4.06265 7.92627 4.31569C7.78 4.56873 7.56977 4.77887 7.31665 4.925L6.95832 5.13334C6.70495 5.27961 6.41755 5.35662 6.12499 5.35662C5.83242 5.35662 5.54502 5.27961 5.29165 5.13334L5.16665 5.06667C4.78421 4.84605 4.32985 4.7862 3.90332 4.90026C3.47679 5.01431 3.11294 5.29294 2.89165 5.675L2.70832 5.99167C2.4877 6.37411 2.42786 6.82847 2.54191 7.255C2.65597 7.68153 2.9346 8.04537 3.31665 8.26667L3.44165 8.35C3.69355 8.49542 3.903 8.70424 4.04919 8.95569C4.19539 9.20714 4.27323 9.49247 4.27499 9.78334V10.2083C4.27615 10.502 4.19969 10.7908 4.05336 11.0454C3.90702 11.3001 3.69599 11.5115 3.44165 11.6583L3.31665 11.7333C2.9346 11.9546 2.65597 12.3185 2.54191 12.745C2.42786 13.1715 2.4877 13.6259 2.70832 14.0083L2.89165 14.325C3.11294 14.7071 3.47679 14.9857 3.90332 15.0998C4.32985 15.2138 4.78421 15.154 5.16665 14.9333L5.29165 14.8667C5.54502 14.7204 5.83242 14.6434 6.12499 14.6434C6.41755 14.6434 6.70495 14.7204 6.95832 14.8667L7.31665 15.075C7.56977 15.2211 7.78 15.4313 7.92627 15.6843C8.07253 15.9374 8.14969 16.2244 8.14999 16.5167V16.6667C8.14999 17.1087 8.32558 17.5326 8.63814 17.8452C8.9507 18.1577 9.37462 18.3333 9.81665 18.3333H10.1833C10.6254 18.3333 11.0493 18.1577 11.3618 17.8452C11.6744 17.5326 11.85 17.1087 11.85 16.6667V16.5167C11.8503 16.2244 11.9274 15.9374 12.0737 15.6843C12.22 15.4313 12.4302 15.2211 12.6833 15.075L13.0417 14.8667C13.295 14.7204 13.5824 14.6434 13.875 14.6434C14.1675 14.6434 14.455 14.7204 14.7083 14.8667L14.8333 14.9333C15.2158 15.154 15.6701 15.2138 16.0967 15.0998C16.5232 14.9857 16.887 14.7071 17.1083 14.325L17.2917 14C17.5123 13.6176 17.5721 13.1632 17.4581 12.7367C17.344 12.3102 17.0654 11.9463 16.6833 11.725L16.5583 11.6583C16.304 11.5115 16.093 11.3001 15.9466 11.0454C15.8003 10.7908 15.7238 10.502 15.725 10.2083V9.79167C15.7238 9.49799 15.8003 9.20921 15.9466 8.95458C16.093 8.69995 16.304 8.4885 16.5583 8.34167L16.6833 8.26667C17.0654 8.04537 17.344 7.68153 17.4581 7.255C17.5721 6.82847 17.5123 6.37411 17.2917 5.99167L17.1083 5.675C16.887 5.29294 16.5232 5.01431 16.0967 4.90026C15.6701 4.7862 15.2158 4.84605 14.8333 5.06667L14.7083 5.13334C14.455 5.27961 14.1675 5.35662 13.875 5.35662C13.5824 5.35662 13.295 5.27961 13.0417 5.13334L12.6833 4.925C12.4302 4.77887 12.22 4.56873 12.0737 4.31569C11.9274 4.06265 11.8503 3.7756 11.85 3.48334V3.33334C11.85 2.8913 11.6744 2.46738 11.3618 2.15482C11.0493 1.84226 10.6254 1.66667 10.1833 1.66667Z",
                "M10 12.5C11.3807 12.5 12.5 11.3807 12.5 10C12.5 8.61929 11.3807 7.5 10 7.5C8.61929 7.5 7.5 8.61929 7.5 10C7.5 11.3807 8.61929 12.5 10 12.5Z",
            ),
        strokeWidth = 1.66667f,
    )

object RipDpiIcons {
    val Home: ImageVector = HomeNavigationIcon
    val Config: ImageVector = ConfigNavigationIcon
    val Logs: ImageVector = LogsNavigationIcon
    val Settings: ImageVector = SettingsNavigationIcon
    val Connected: ImageVector = Icons.Filled.Check
    val Offline: ImageVector = Icons.Filled.Close
    val Vpn: ImageVector = Icons.Filled.Warning
    val Dns: ImageVector = Icons.Filled.Search
    val Advanced: ImageVector = Icons.Filled.MoreVert
    val Search: ImageVector = Icons.Filled.Search
    val Warning: ImageVector = Icons.Filled.Warning
    val Error: ImageVector = Icons.Filled.Close
    val Info: ImageVector = Icons.Filled.Info
    val Lock: ImageVector = Icons.Filled.Lock
    val Copy: ImageVector = Icons.Filled.Share
    val Share: ImageVector = Icons.Filled.Share
    val ChevronRight: ImageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight
    val ChevronLeft: ImageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft
    val Back: ImageVector = Icons.AutoMirrored.Filled.ArrowBack
    val Overflow: ImageVector = Icons.Filled.MoreVert
    val Close: ImageVector = Icons.Filled.Close
    val Check: ImageVector = Icons.Filled.Check
}
