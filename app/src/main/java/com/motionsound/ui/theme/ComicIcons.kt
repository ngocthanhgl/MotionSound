package com.motionsound.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

object ComicIcons {
    private val ink = SolidColor(Color.Black)

    private fun Path.rect(x: Float, y: Float, w: Float, h: Float): Path {
        moveTo(x, y)
        lineTo(x + w, y)
        lineTo(x + w, y + h)
        lineTo(x, y + h)
        close()
        return this
    }

    private fun Path.poly(vararg pts: Float): Path {
        moveTo(pts[0], pts[1])
        var i = 2
        while (i < pts.size) {
            lineTo(pts[i], pts[i + 1])
            i += 2
        }
        close()
        return this
    }

    private fun Path.lines(vararg pts: Float): Path {
        moveTo(pts[0], pts[1])
        var i = 2
        while (i < pts.size) {
            lineTo(pts[i], pts[i + 1])
            i += 2
        }
        return this
    }

    private fun filled(vararg ps: Path): ImageVector.Builder.() -> Unit = {
        for (p in ps) addPath(p, pathFill = ink)
    }

    private fun stroked(w: Float, vararg ps: Path): ImageVector.Builder.() -> Unit = {
        for (p in ps) addPath(
            p,
            pathStroke = ink,
            pathStrokeLineWidth = w,
            pathStrokeLineCap = StrokeCap.Square,
            pathStrokeLineJoin = StrokeJoin.Miter
        )
    }

    private fun build(vararg ops: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = "ComicIcon",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            for (op in ops) op()
        }.build()

    val PlayArrow by lazy { build(filled(Path().poly(6f, 4f, 20f, 12f, 6f, 20f))) }

    val Pause by lazy { build(filled(Path().rect(5f, 4f, 6f, 16f), Path().rect(13f, 4f, 6f, 16f))) }

    val SkipNext by lazy { build(filled(Path().rect(4f, 4f, 4f, 16f), Path().poly(10f, 4f, 22f, 12f, 10f, 20f))) }

    val SkipPrevious by lazy { build(filled(Path().poly(2f, 4f, 14f, 12f, 2f, 20f), Path().rect(16f, 4f, 4f, 16f))) }

    val Shuffle by lazy {
        build(
            filled(
                Path().rect(3f, 4.5f, 9f, 3f),
                Path().poly(12f, 2.5f, 12f, 7.5f, 18f, 5f),
                Path().rect(12f, 16.5f, 9f, 3f),
                Path().poly(5f, 18f, 11f, 15.5f, 11f, 20.5f)
            )
        )
    }

    val Repeat by lazy {
        build(
            stroked(2.5f, Path().lines(7f, 9f, 7f, 5f, 17f, 5f, 17f, 14f, 11f, 14f)),
            filled(Path().poly(15f, 12f, 15f, 16f, 11f, 14f))
        )
    }

    val MusicNote by lazy {
        build(
            filled(
                Path().rect(10f, 3f, 3f, 13f),
                Path().rect(10f, 3f, 8f, 3f),
                Path().rect(10f, 6f, 5f, 3f),
                Path().poly(7f, 19f, 10f, 16f, 13f, 19f, 10f, 22f),
                Path().poly(14f, 17f, 17f, 14f, 20f, 17f, 17f, 20f)
            )
        )
    }

    val QueueMusic by lazy {
        build(
            filled(
                Path().rect(3f, 5f, 10f, 3f),
                Path().rect(3f, 11f, 12f, 3f),
                Path().rect(3f, 17f, 9f, 3f),
                Path().rect(17f, 12f, 3f, 7f),
                Path().poly(14f, 17f, 17f, 14f, 20f, 17f, 17f, 20f)
            )
        )
    }

    val PlaylistAdd by lazy {
        build(
            filled(
                Path().rect(3f, 5f, 14f, 3f),
                Path().rect(3f, 11f, 14f, 3f),
                Path().rect(3f, 17f, 7f, 3f),
                Path().rect(13f, 16.5f, 6f, 3f),
                Path().rect(14.5f, 15f, 3f, 6f)
            )
        )
    }

    val Add by lazy { build(filled(Path().rect(4f, 10.5f, 16f, 3f), Path().rect(10.5f, 4f, 3f, 16f))) }

    val Check by lazy { build(stroked(3.5f, Path().lines(5f, 13f, 9f, 17f, 19f, 6f))) }

    val CheckCircle by lazy {
        build(
            stroked(3f, Path().rect(3f, 3f, 18f, 18f)),
            stroked(3f, Path().lines(7f, 12.5f, 10.5f, 16f, 17f, 8f))
        )
    }

    val Delete by lazy {
        val body = Path().apply {
            rect(6f, 9f, 12f, 12f)
            rect(9.5f, 11f, 2.5f, 8f)
            rect(12.5f, 11f, 2.5f, 8f)
        }
        build(
            filled(Path().rect(9f, 3f, 6f, 3f), Path().rect(4f, 6f, 16f, 3f)),
            {
                addPath(body, pathFillType = PathFillType.EvenOdd, pathFill = ink)
            }
        )
    }

    val Clear by lazy {
        build(
            filled(
                Path().poly(5f, 4f, 8f, 4f, 20f, 16f, 17f, 16f),
                Path().poly(4f, 19f, 7f, 19f, 19f, 7f, 16f, 7f)
            )
        )
    }

    val Search by lazy {
        build(
            stroked(3f, Path().rect(4f, 4f, 12f, 12f)),
            filled(Path().poly(15f, 15f, 18f, 15f, 22f, 19f, 19f, 19f))
        )
    }

    val Refresh by lazy {
        build(
            stroked(2.5f, Path().rect(6f, 5f, 12f, 14f)),
            filled(Path().poly(4f, 17f, 8f, 15f, 8f, 19f))
        )
    }

    val Download by lazy {
        build(
            filled(
                Path().rect(10.5f, 3f, 3f, 10f),
                Path().poly(7f, 10f, 17f, 10f, 12f, 17f),
                Path().rect(4f, 18f, 16f, 3f)
            )
        )
    }

    val ArrowBack by lazy {
        build(filled(Path().rect(10f, 10.5f, 11f, 3f), Path().poly(4f, 12f, 10f, 5f, 10f, 19f)))
    }

    val KeyboardArrowRight by lazy {
        build(filled(Path().rect(5f, 10.5f, 8f, 3f), Path().poly(13f, 5f, 13f, 19f, 19f, 12f)))
    }

    val Settings by lazy {
        build(
            filled(
                Path().rect(9f, 9f, 6f, 6f),
                Path().rect(9f, 5f, 6f, 3f),
                Path().rect(9f, 16f, 6f, 3f),
                Path().rect(5f, 9f, 3f, 6f),
                Path().rect(16f, 9f, 3f, 6f),
                Path().rect(6f, 6f, 3f, 3f),
                Path().rect(15f, 6f, 3f, 3f),
                Path().rect(6f, 15f, 3f, 3f),
                Path().rect(15f, 15f, 3f, 3f)
            )
        )
    }

    val Speed by lazy {
        build(
            stroked(2.5f, Path().lines(4f, 19f, 4f, 7f, 20f, 7f, 20f, 19f)),
            filled(
                Path().poly(8f, 19f, 16f, 19f, 12f, 9f),
                Path().rect(6f, 7f, 2f, 3f),
                Path().rect(11f, 7f, 2f, 3f),
                Path().rect(16f, 7f, 2f, 3f)
            )
        )
    }

    val Memory by lazy {
        build(
            stroked(2.5f, Path().rect(7f, 7f, 10f, 10f)),
            filled(
                Path().rect(5f, 9f, 2f, 2f),
                Path().rect(5f, 13f, 2f, 2f),
                Path().rect(17f, 9f, 2f, 2f),
                Path().rect(17f, 13f, 2f, 2f),
                Path().rect(9f, 5f, 2f, 2f),
                Path().rect(13f, 5f, 2f, 2f),
                Path().rect(9f, 17f, 2f, 2f),
                Path().rect(13f, 17f, 2f, 2f),
                Path().rect(11f, 7f, 2f, 3f)
            )
        )
    }

    val BugReport by lazy {
        build(
            stroked(
                2.5f,
                Path().lines(8f, 5f, 5f, 1f),
                Path().lines(16f, 5f, 19f, 1f),
                Path().rect(7f, 4f, 10f, 3f),
                Path().rect(8f, 7f, 8f, 10f),
                Path().lines(5f, 9f, 3f, 12f),
                Path().lines(19f, 9f, 21f, 12f),
                Path().lines(5f, 12f, 3f, 15f),
                Path().lines(19f, 12f, 21f, 15f)
            )
        )
    }

    val LocationOn by lazy {
        build(
            stroked(2.5f, Path().rect(8f, 3f, 8f, 8f)),
            filled(Path().poly(7f, 11f, 17f, 11f, 12f, 19f), Path().rect(10.5f, 6f, 3f, 3f))
        )
    }

    val BatterySaver by lazy {
        build(
            stroked(2.5f, Path().rect(3f, 8f, 16f, 8f)),
            filled(
                Path().rect(19f, 10f, 2.5f, 4f),
                Path().poly(11f, 9f, 8.5f, 12f, 10.5f, 12f, 9.5f, 15f, 13f, 12f, 11f, 12f)
            )
        )
    }

    val PhoneAndroid by lazy {
        build(
            stroked(2.5f, Path().rect(6f, 3f, 12f, 18f)),
            filled(Path().rect(8f, 5.5f, 8f, 10.5f), Path().rect(10f, 16.5f, 4f, 2.5f))
        )
    }

    val Info by lazy {
        build(
            stroked(2.5f, Path().rect(6f, 4f, 12f, 16f)),
            filled(Path().rect(10.5f, 7.5f, 3f, 3f), Path().rect(10.5f, 12f, 3f, 5f))
        )
    }

    val Code by lazy {
        build(
            filled(
                Path().poly(5f, 12f, 10f, 5f, 13f, 5f, 8f, 12f, 13f, 19f, 10f, 19f),
                Path().poly(19f, 12f, 14f, 5f, 11f, 5f, 16f, 12f, 11f, 19f, 14f, 19f)
            )
        )
    }

    val Notifications by lazy {
        build(
            stroked(2.5f, Path().poly(7f, 8f, 7f, 6f, 17f, 6f, 17f, 8f, 18f, 13f, 6f, 13f)),
            filled(Path().rect(6f, 13f, 12f, 2f), Path().rect(10.5f, 16f, 3f, 2.5f))
        )
    }
}
