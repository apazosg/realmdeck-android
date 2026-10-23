package app.gamenative

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Original vector glyphs for the functional launcher, drawn from the approved
 * 32x32 contact sheet (emblem, download, storage, account, display,
 * graphics). No Blizzard artwork, fonts, PNGs or network-fetched resources.
 *
 * Paths are monochrome (black paint): callers render them through Icon with
 * the inherited content color (button gold), decorative only
 * (contentDescription = null).
 */
object ForeverFunctionalIcons {
    val Emblem: ImageVector by lazy {
        ImageVector.Builder(
            name = "ForeverEmblem",
            defaultWidth = 32.dp,
            defaultHeight = 32.dp,
            viewportWidth = 32f,
            viewportHeight = 32f,
        ).addPath(
            pathData = addPathNodes("m16 2 12 5-2 16-10 7L6 23 4 7Z"),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.5f,
            strokeLineJoin = StrokeJoin.Round,
        ).addPath(
            pathData = addPathNodes("m16 8 5 8-5 9-5-9Z"),
            fill = SolidColor(Color.Black),
            fillAlpha = 0.24f,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.5f,
            strokeLineJoin = StrokeJoin.Round,
        ).build()
    }

    val Download: ImageVector by lazy {
        ImageVector.Builder(
            name = "ForeverDownload",
            defaultWidth = 32.dp,
            defaultHeight = 32.dp,
            viewportWidth = 32f,
            viewportHeight = 32f,
        ).addPath(
            pathData = addPathNodes("M16 3v17m-7-7 7 7 7-7M4 22v6h24v-6"),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()
    }

    val Storage: ImageVector by lazy {
        ImageVector.Builder(
            name = "ForeverStorage",
            defaultWidth = 32.dp,
            defaultHeight = 32.dp,
            viewportWidth = 32f,
            viewportHeight = 32f,
        ).addPath(
            pathData = addPathNodes("M10 11V3h12v8M14 3v4m4-4v4"),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).addPath(
            pathData = addPathNodes("M11 11H21Q25 11 25 15V26Q25 30 21 30H11Q7 30 7 26V15Q7 11 11 11Z"),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).addPath(
            pathData = addPathNodes("M17.3 24A1.3 1.3 0 1 1 14.7 24A1.3 1.3 0 1 1 17.3 24Z"),
            fill = SolidColor(Color.Black),
        ).build()
    }

    val Account: ImageVector by lazy {
        ImageVector.Builder(
            name = "ForeverAccount",
            defaultWidth = 32.dp,
            defaultHeight = 32.dp,
            viewportWidth = 32f,
            viewportHeight = 32f,
        ).addPath(
            pathData = addPathNodes("M21 10A5 5 0 1 1 11 10A5 5 0 1 1 21 10Z"),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).addPath(
            pathData = addPathNodes("M5 29v-3c0-10 22-10 22 0v3"),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).addPath(
            pathData = addPathNodes("m16 21 3 4-3 4-3-4Z"),
            fill = SolidColor(Color.Black),
            fillAlpha = 0.24f,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()
    }

    val Display: ImageVector by lazy {
        ImageVector.Builder(
            name = "ForeverDisplay",
            defaultWidth = 32.dp,
            defaultHeight = 32.dp,
            viewportWidth = 32f,
            viewportHeight = 32f,
        ).addPath(
            pathData = addPathNodes("M5 4H27Q29 4 29 6V20Q29 22 27 22H5Q3 22 3 20V6Q3 4 5 4Z"),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).addPath(
            pathData = addPathNodes("M16 22v7m-6 0h12M7 8h4"),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()
    }

    val Graphics: ImageVector by lazy {
        ImageVector.Builder(
            name = "ForeverGraphics",
            defaultWidth = 32.dp,
            defaultHeight = 32.dp,
            viewportWidth = 32f,
            viewportHeight = 32f,
        ).addPath(
            pathData = addPathNodes("M10 8H22Q24 8 24 10V22Q24 24 22 24H10Q8 24 8 22V10Q8 8 10 8Z"),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).addPath(
            pathData = addPathNodes("M11 3v5m10-5v5M11 24v5m10-5v5M3 11h5m-5 10h5m16-10h5m-5 10h5"),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).addPath(
            pathData = addPathNodes("m18 11-5 6h4l-2 5 6-8h-4Z"),
            fill = SolidColor(Color.Black),
            fillAlpha = 0.24f,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()
    }
}
