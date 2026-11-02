package com.memeget.nativeapp.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.unit.dp

// Only the 22 Material 24x24 vector icons Memeget actually paints on screen —
// replacing the 11,400-class material-icons-extended dependency without losing a single pixel.

internal object MemegetIcons {

    private fun icon(name: String, autoMirror: Boolean = false, build: PathBuilder.() -> Unit): ImageVector {
        val pb = PathBuilder().apply(build)
        return ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = autoMirror,
        ).addPath(
            pathData = pb.nodes,
            fill = SolidColor(Color.Black),
        ).build()
    }

    val Add: ImageVector by lazy {
        icon("Add") {
            moveTo(19f, 13f); horizontalLineToRelative(-6f); verticalLineToRelative(6f)
            horizontalLineToRelative(-2f); verticalLineToRelative(-6f); horizontalLineTo(5f)
            verticalLineToRelative(-2f); horizontalLineToRelative(6f); verticalLineTo(5f)
            horizontalLineToRelative(2f); verticalLineToRelative(6f); horizontalLineToRelative(6f)
            verticalLineToRelative(2f); close()
        }
    }

    val ArrowBack: ImageVector by lazy {
        icon("ArrowBack", autoMirror = true) {
            moveTo(20f, 11f); horizontalLineTo(7.83f); lineToRelative(5.59f, -5.59f)
            lineTo(12f, 4f); lineToRelative(-8f, 8f); lineToRelative(8f, 8f)
            lineToRelative(1.41f, -1.41f); lineTo(7.83f, 13f); horizontalLineTo(20f)
            verticalLineToRelative(-2f); close()
        }
    }

    val ArrowForward: ImageVector by lazy {
        icon("ArrowForward", autoMirror = true) {
            moveTo(12f, 4f); lineToRelative(-1.41f, 1.41f); lineTo(16.17f, 11f)
            horizontalLineTo(4f); verticalLineToRelative(2f); horizontalLineToRelative(12.17f)
            lineToRelative(-5.58f, 5.59f); lineTo(12f, 20f); lineToRelative(8f, -8f); close()
        }
    }

    val Close: ImageVector by lazy {
        icon("Close") {
            moveTo(19f, 6.41f); lineTo(17.59f, 5f); lineTo(12f, 10.59f)
            lineTo(6.41f, 5f); lineTo(5f, 6.41f); lineTo(10.59f, 12f)
            lineTo(5f, 17.59f); lineTo(6.41f, 19f); lineTo(12f, 13.41f)
            lineTo(17.59f, 19f); lineTo(19f, 17.59f); lineTo(13.41f, 12f); close()
        }
    }

    val ContentCopy: ImageVector by lazy {
        icon("ContentCopy") {
            moveTo(16f, 1f); horizontalLineTo(4f); curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
            verticalLineToRelative(14f); horizontalLineToRelative(2f); verticalLineTo(3f)
            horizontalLineToRelative(12f); verticalLineTo(1f); close()
            moveTo(19f, 5f); horizontalLineTo(8f); curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
            verticalLineToRelative(14f); curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
            horizontalLineToRelative(11f); curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            verticalLineTo(7f); curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f); close()
            moveTo(19f, 21f); horizontalLineTo(8f); verticalLineTo(7f)
            horizontalLineToRelative(11f); verticalLineToRelative(14f); close()
        }
    }

    val Dashboard: ImageVector by lazy {
        icon("Dashboard") {
            moveTo(3f, 13f); horizontalLineToRelative(8f); verticalLineTo(3f)
            horizontalLineTo(3f); verticalLineToRelative(10f); close()
            moveTo(3f, 21f); horizontalLineToRelative(8f); verticalLineToRelative(-6f)
            horizontalLineTo(3f); verticalLineToRelative(6f); close()
            moveTo(13f, 21f); horizontalLineToRelative(8f); verticalLineTo(11f)
            horizontalLineToRelative(-8f); verticalLineToRelative(10f); close()
            moveTo(13f, 3f); verticalLineToRelative(6f); horizontalLineToRelative(8f)
            verticalLineTo(3f); horizontalLineToRelative(-8f); close()
        }
    }

    val Delete: ImageVector by lazy {
        icon("Delete") {
            moveTo(6f, 19f); curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
            horizontalLineToRelative(8f); curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            verticalLineTo(7f); horizontalLineTo(6f); verticalLineToRelative(12f); close()
            moveTo(19f, 4f); horizontalLineToRelative(-3.5f); lineToRelative(-1f, -1f)
            horizontalLineToRelative(-5f); lineToRelative(-1f, 1f); horizontalLineTo(5f)
            verticalLineToRelative(2f); horizontalLineToRelative(14f); verticalLineTo(4f); close()
        }
    }

    val Folder: ImageVector by lazy {
        icon("Folder") {
            moveTo(10f, 4f); horizontalLineTo(4f); curveToRelative(-1.1f, 0f, -1.99f, 0.9f, -1.99f, 2f)
            lineTo(2f, 18f); curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
            horizontalLineToRelative(16f); curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            verticalLineTo(8f); curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
            horizontalLineToRelative(-8f); lineToRelative(-2f, -2f); close()
        }
    }

    val FormatAlignCenter: ImageVector by lazy {
        icon("FormatAlignCenter") {
            moveTo(7f, 15f); verticalLineToRelative(2f); horizontalLineToRelative(10f)
            verticalLineToRelative(-2f); horizontalLineTo(7f); close()
            moveTo(3f, 21f); horizontalLineToRelative(18f); verticalLineToRelative(-2f)
            horizontalLineTo(3f); verticalLineToRelative(2f); close()
            moveTo(3f, 13f); horizontalLineToRelative(18f); verticalLineToRelative(-2f)
            horizontalLineTo(3f); verticalLineToRelative(2f); close()
            moveTo(7f, 7f); verticalLineToRelative(2f); horizontalLineToRelative(10f)
            verticalLineTo(7f); horizontalLineTo(7f); close()
            moveTo(3f, 3f); verticalLineToRelative(2f); horizontalLineToRelative(18f)
            verticalLineTo(3f); horizontalLineTo(3f); close()
        }
    }

    val FormatAlignLeft: ImageVector by lazy {
        icon("FormatAlignLeft") {
            moveTo(15f, 15f); horizontalLineTo(3f); verticalLineToRelative(2f)
            horizontalLineToRelative(12f); verticalLineToRelative(-2f); close()
            moveTo(15f, 7f); horizontalLineTo(3f); verticalLineToRelative(2f)
            horizontalLineToRelative(12f); verticalLineTo(7f); close()
            moveTo(3f, 13f); horizontalLineToRelative(18f); verticalLineToRelative(-2f)
            horizontalLineTo(3f); verticalLineToRelative(2f); close()
            moveTo(3f, 21f); horizontalLineToRelative(18f); verticalLineToRelative(-2f)
            horizontalLineTo(3f); verticalLineToRelative(2f); close()
            moveTo(3f, 3f); verticalLineToRelative(2f); horizontalLineToRelative(18f)
            verticalLineTo(3f); horizontalLineTo(3f); close()
        }
    }

    val FormatAlignRight: ImageVector by lazy {
        icon("FormatAlignRight") {
            moveTo(3f, 21f); horizontalLineToRelative(18f); verticalLineToRelative(-2f)
            horizontalLineTo(3f); verticalLineToRelative(2f); close()
            moveTo(9f, 17f); horizontalLineToRelative(12f); verticalLineToRelative(-2f)
            horizontalLineTo(9f); verticalLineToRelative(2f); close()
            moveTo(3f, 13f); horizontalLineToRelative(18f); verticalLineToRelative(-2f)
            horizontalLineTo(3f); verticalLineToRelative(2f); close()
            moveTo(9f, 9f); horizontalLineToRelative(12f); verticalLineTo(7f)
            horizontalLineTo(9f); verticalLineToRelative(2f); close()
            moveTo(3f, 3f); verticalLineToRelative(2f); horizontalLineToRelative(18f)
            verticalLineTo(3f); horizontalLineTo(3f); close()
        }
    }

    val GridView: ImageVector by lazy {
        icon("GridView") {
            moveTo(3f, 3f); verticalLineToRelative(8f); horizontalLineToRelative(8f)
            verticalLineTo(3f); horizontalLineTo(3f); close()
            moveTo(9f, 9f); horizontalLineTo(5f); verticalLineTo(5f)
            horizontalLineToRelative(4f); verticalLineToRelative(4f); close()
            moveTo(3f, 13f); verticalLineToRelative(8f); horizontalLineToRelative(8f)
            verticalLineToRelative(-8f); horizontalLineTo(3f); close()
            moveTo(9f, 19f); horizontalLineTo(5f); verticalLineToRelative(-4f)
            horizontalLineToRelative(4f); verticalLineToRelative(4f); close()
            moveTo(13f, 3f); verticalLineToRelative(8f); horizontalLineToRelative(8f)
            verticalLineTo(3f); horizontalLineToRelative(-8f); close()
            moveTo(19f, 9f); horizontalLineToRelative(-4f); verticalLineTo(5f)
            horizontalLineToRelative(4f); verticalLineToRelative(4f); close()
            moveTo(13f, 13f); verticalLineToRelative(8f); horizontalLineToRelative(8f)
            verticalLineToRelative(-8f); horizontalLineToRelative(-8f); close()
            moveTo(19f, 19f); horizontalLineToRelative(-4f); verticalLineToRelative(-4f)
            horizontalLineToRelative(4f); verticalLineToRelative(4f); close()
        }
    }

    val KeyboardArrowDown: ImageVector by lazy {
        icon("KeyboardArrowDown") {
            moveTo(7.41f, 8.59f); lineTo(12f, 13.17f); lineToRelative(4.59f, -4.58f)
            lineTo(18f, 10f); lineToRelative(-6f, 6f); lineToRelative(-6f, -6f); close()
        }
    }

    val KeyboardArrowUp: ImageVector by lazy {
        icon("KeyboardArrowUp") {
            moveTo(7.41f, 15.41f); lineTo(12f, 10.83f); lineToRelative(4.59f, 4.58f)
            lineTo(18f, 14f); lineToRelative(-6f, -6f); lineToRelative(-6f, 6f); close()
        }
    }

    val Pause: ImageVector by lazy {
        icon("Pause") {
            moveTo(6f, 19f); horizontalLineToRelative(4f); verticalLineTo(5f)
            horizontalLineTo(6f); verticalLineToRelative(14f); close()
            moveTo(14f, 5f); verticalLineToRelative(14f); horizontalLineToRelative(4f)
            verticalLineTo(5f); horizontalLineToRelative(-4f); close()
        }
    }

    val PlayArrow: ImageVector by lazy {
        icon("PlayArrow") {
            moveTo(8f, 5f); verticalLineToRelative(14f); lineToRelative(11f, -7f); close()
        }
    }

    val Redo: ImageVector by lazy {
        icon("Redo") {
            moveTo(18.4f, 10.6f)
            curveTo(16.55f, 8.99f, 14.15f, 8f, 11.5f, 8f)
            curveToRelative(-4.65f, 0f, -8.58f, 3.03f, -9.96f, 7.22f)
            lineTo(3.9f, 16f)
            curveToRelative(1.05f, -3.19f, 4.05f, -5.5f, 7.6f, -5.5f)
            curveToRelative(1.95f, 0f, 3.73f, 0.72f, 5.12f, 1.88f)
            lineTo(13f, 16f); horizontalLineToRelative(9f); verticalLineTo(7f)
            lineToRelative(-3.6f, 3.6f); close()
        }
    }

    val Refresh: ImageVector by lazy {
        icon("Refresh") {
            moveTo(17.65f, 6.35f)
            curveTo(16.2f, 4.9f, 14.21f, 4f, 12f, 4f)
            curveToRelative(-4.42f, 0f, -7.99f, 3.58f, -7.99f, 8f)
            reflectiveCurveToRelative(3.57f, 8f, 7.99f, 8f)
            curveToRelative(3.73f, 0f, 6.84f, -2.55f, 7.73f, -6f)
            horizontalLineToRelative(-2.08f)
            curveToRelative(-0.82f, 2.33f, -3.04f, 4f, -5.65f, 4f)
            curveToRelative(-3.31f, 0f, -6f, -2.69f, -6f, -6f)
            reflectiveCurveToRelative(2.69f, -6f, 6f, -6f)
            curveToRelative(1.66f, 0f, 3.14f, 0.69f, 4.22f, 1.78f)
            lineTo(13f, 11f); horizontalLineToRelative(7f); verticalLineTo(4f)
            lineToRelative(-2.35f, 2.35f); close()
        }
    }

    val RotateRight: ImageVector by lazy {
        icon("RotateRight") {
            moveTo(15.55f, 5.55f); lineTo(11f, 1f); verticalLineToRelative(3.07f)
            curveTo(7.06f, 4.56f, 4f, 7.92f, 4f, 12f)
            reflectiveCurveToRelative(3.05f, 7.44f, 7f, 7.93f)
            verticalLineToRelative(-2.02f)
            curveToRelative(-2.84f, -0.48f, -5f, -2.94f, -5f, -5.91f)
            reflectiveCurveToRelative(2.16f, -5.43f, 5f, -5.91f)
            verticalLineTo(10f); lineToRelative(4.55f, -4.45f); close()
            moveTo(19.93f, 11f)
            curveToRelative(-0.17f, -1.39f, -0.72f, -2.73f, -1.62f, -3.89f)
            lineToRelative(-1.42f, 1.42f)
            curveToRelative(0.54f, 0.75f, 0.88f, 1.6f, 1.02f, 2.47f)
            horizontalLineToRelative(2.02f); close()
            moveTo(13f, 17.9f); verticalLineToRelative(2.02f)
            curveToRelative(1.39f, -0.17f, 2.74f, -0.71f, 3.9f, -1.61f)
            lineToRelative(-1.44f, -1.44f)
            curveToRelative(-0.75f, 0.54f, -1.59f, 0.89f, -2.46f, 1.03f); close()
            moveTo(16.89f, 15.48f); lineToRelative(1.42f, 1.41f)
            curveToRelative(0.9f, -1.16f, 1.45f, -2.5f, 1.62f, -3.89f)
            horizontalLineToRelative(-2.02f)
            curveToRelative(-0.14f, 0.87f, -0.48f, 1.72f, -1.02f, 2.48f); close()
        }
    }

    val Undo: ImageVector by lazy {
        icon("Undo") {
            moveTo(12.5f, 8f)
            curveToRelative(-2.65f, 0f, -5.05f, 0.99f, -6.9f, 2.6f)
            lineTo(2f, 7f); verticalLineToRelative(9f); horizontalLineToRelative(9f)
            lineToRelative(-3.62f, -3.62f)
            curveToRelative(1.39f, -1.16f, 3.16f, -1.88f, 5.12f, -1.88f)
            curveToRelative(3.54f, 0f, 6.55f, 2.31f, 7.6f, 5.5f)
            lineToRelative(2.37f, -0.78f)
            curveTo(21.08f, 11.03f, 17.15f, 8f, 12.5f, 8f); close()
        }
    }

    val VolumeMute: ImageVector by lazy {
        icon("VolumeMute") {
            moveTo(7f, 9f); verticalLineToRelative(6f); horizontalLineToRelative(4f)
            lineToRelative(5f, 5f); verticalLineTo(4f); lineToRelative(-5f, 5f)
            horizontalLineTo(7f); close()
        }
    }

    val VolumeUp: ImageVector by lazy {
        icon("VolumeUp") {
            moveTo(3f, 9f); verticalLineToRelative(6f); horizontalLineToRelative(4f)
            lineToRelative(5f, 5f); verticalLineTo(4f); lineTo(7f, 9f); horizontalLineTo(3f); close()
            moveTo(16.5f, 12f)
            curveToRelative(0f, -1.77f, -1.02f, -3.29f, -2.5f, -4.03f)
            verticalLineToRelative(8.05f)
            curveToRelative(1.48f, -0.73f, 2.5f, -2.25f, 2.5f, -4.02f); close()
            moveTo(14f, 3.23f); verticalLineToRelative(2.06f)
            curveToRelative(2.89f, 0.86f, 5f, 3.54f, 5f, 6.71f)
            reflectiveCurveToRelative(-2.11f, 5.85f, -5f, 6.71f)
            verticalLineToRelative(2.06f)
            curveToRelative(4.01f, -0.91f, 7f, -4.49f, 7f, -8.77f)
            reflectiveCurveToRelative(-2.99f, -7.86f, -7f, -8.77f); close()
        }
    }
}
