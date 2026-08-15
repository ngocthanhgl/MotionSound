package com.motionsound.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object ComicIcons {
    private val ink = SolidColor(Color.Black)

    private fun PathBuilder.rect(x: Float, y: Float, w: Float, h: Float): PathBuilder {
        moveTo(x, y)
        lineTo(x + w, y)
        lineTo(x + w, y + h)
        lineTo(x, y + h)
        close()
        return this
    }

    private fun PathBuilder.poly(vararg pts: Float): PathBuilder {
        moveTo(pts[0], pts[1])
        var i = 2
        while (i < pts.size) {
            lineTo(pts[i], pts[i + 1])
            i += 2
        }
        close()
        return this
    }

    private fun PathBuilder.lines(vararg pts: Float): PathBuilder {
        moveTo(pts[0], pts[1])
        var i = 2
        while (i < pts.size) {
            lineTo(pts[i], pts[i + 1])
            i += 2
        }
        return this
    }

    private fun filled(vararg blocks: PathBuilder.() -> Unit): ImageVector.Builder.() -> Unit = {
        for (b in blocks) path(fill = ink) { b() }
    }

    private fun stroked(w: Float, vararg blocks: PathBuilder.() -> Unit): ImageVector.Builder.() -> Unit = {
        for (b in blocks) path(
            stroke = ink,
            strokeLineWidth = w,
            strokeLineCap = StrokeCap.Square,
            strokeLineJoin = StrokeJoin.Miter
        ) { b() }
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

    val PlayArrow by lazy { build(filled({ poly(6f, 4f, 20f, 12f, 6f, 20f) })) }

    val Pause by lazy { build(filled({ rect(5f, 4f, 6f, 16f) }, { rect(13f, 4f, 6f, 16f) })) }

    val SkipNext by lazy { build(filled({ rect(4f, 4f, 4f, 16f) }, { poly(10f, 4f, 22f, 12f, 10f, 20f) })) }

    val SkipPrevious by lazy { build(filled({ poly(2f, 4f, 14f, 12f, 2f, 20f) }, { rect(16f, 4f, 4f, 16f) })) }

    val Shuffle by lazy {
        build(
            filled(
                { rect(3f, 4.5f, 9f, 3f) },
                { poly(12f, 2.5f, 12f, 7.5f, 18f, 5f) },
                { rect(12f, 16.5f, 9f, 3f) },
                { poly(5f, 18f, 11f, 15.5f, 11f, 20.5f) }
            )
        )
    }

    val Repeat by lazy {
        build(
            stroked(2.5f, { lines(7f, 9f, 7f, 5f, 17f, 5f, 17f, 14f, 11f, 14f) }),
            filled({ poly(15f, 12f, 15f, 16f, 11f, 14f) })
        )
    }

    val MusicNote by lazy {
        build(
            filled(
                { rect(10f, 3f, 3f, 13f) },
                { rect(10f, 3f, 8f, 3f) },
                { rect(10f, 6f, 5f, 3f) },
                { poly(7f, 19f, 10f, 16f, 13f, 19f, 10f, 22f) },
                { poly(14f, 17f, 17f, 14f, 20f, 17f, 17f, 20f) }
            )
        )
    }

    val QueueMusic by lazy {
        build(
            filled(
                { rect(3f, 5f, 10f, 3f) },
                { rect(3f, 11f, 12f, 3f) },
                { rect(3f, 17f, 9f, 3f) },
                { rect(17f, 12f, 3f, 7f) },
                { poly(14f, 17f, 17f, 14f, 20f, 17f, 17f, 20f) }
            )
        )
    }

    val PlaylistAdd by lazy {
        build(
            filled(
                { rect(3f, 5f, 14f, 3f) },
                { rect(3f, 11f, 14f, 3f) },
                { rect(3f, 17f, 7f, 3f) },
                { rect(13f, 16.5f, 6f, 3f) },
                { rect(14.5f, 15f, 3f, 6f) }
            )
        )
    }

    val Add by lazy { build(filled({ rect(4f, 10.5f, 16f, 3f) }, { rect(10.5f, 4f, 3f, 16f) })) }

    val Check by lazy { build(stroked(3.5f, { lines(5f, 13f, 9f, 17f, 19f, 6f) })) }

    val CheckCircle by lazy {
        build(
            stroked(3f, { rect(3f, 3f, 18f, 18f) }, { lines(7f, 12.5f, 10.5f, 16f, 17f, 8f) })
        )
    }

    val Delete by lazy {
        build(
            filled({ rect(9f, 3f, 6f, 3f) }, { rect(4f, 6f, 16f, 3f) }),
            {
                path(pathFillType = PathFillType.EvenOdd, fill = ink) {
                    rect(6f, 9f, 12f, 12f)
                    rect(9.5f, 11f, 2.5f, 8f)
                    rect(12.5f, 11f, 2.5f, 8f)
                }
            }
        )
    }

    val Clear by lazy {
        build(
            filled(
                { poly(5f, 4f, 8f, 4f, 20f, 16f, 17f, 16f) },
                { poly(4f, 19f, 7f, 19f, 19f, 7f, 16f, 7f) }
            )
        )
    }

    val Search by lazy {
        build(
            stroked(3f, { rect(4f, 4f, 12f, 12f) }),
            filled({ poly(15f, 15f, 18f, 15f, 22f, 19f, 19f, 19f) })
        )
    }

    val Refresh by lazy {
        build(
            stroked(2.5f, { rect(6f, 5f, 12f, 14f) }),
            filled({ poly(4f, 17f, 8f, 15f, 8f, 19f) })
        )
    }

    val Download by lazy {
        build(
            filled(
                { rect(10.5f, 3f, 3f, 10f) },
                { poly(7f, 10f, 17f, 10f, 12f, 17f) },
                { rect(4f, 18f, 16f, 3f) }
            )
        )
    }

    val ArrowBack by lazy {
        build(filled({ rect(10f, 10.5f, 11f, 3f) }, { poly(4f, 12f, 10f, 5f, 10f, 19f) }))
    }

    val KeyboardArrowRight by lazy {
        build(filled({ rect(5f, 10.5f, 8f, 3f) }, { poly(13f, 5f, 13f, 19f, 19f, 12f) }))
    }

    val Settings by lazy {
        build(
            filled(
                { rect(9f, 9f, 6f, 6f) },
                { rect(9f, 5f, 6f, 3f) },
                { rect(9f, 16f, 6f, 3f) },
                { rect(5f, 9f, 3f, 6f) },
                { rect(16f, 9f, 3f, 6f) },
                { rect(6f, 6f, 3f, 3f) },
                { rect(15f, 6f, 3f, 3f) },
                { rect(6f, 15f, 3f, 3f) },
                { rect(15f, 15f, 3f, 3f) }
            )
        )
    }

    val Speed by lazy {
        build(
            stroked(2.5f, { lines(4f, 19f, 4f, 7f, 20f, 7f, 20f, 19f) }),
            filled(
                { poly(8f, 19f, 16f, 19f, 12f, 9f) },
                { rect(6f, 7f, 2f, 3f) },
                { rect(11f, 7f, 2f, 3f) },
                { rect(16f, 7f, 2f, 3f) }
            )
        )
    }

    val Memory by lazy {
        build(
            stroked(2.5f, { rect(7f, 7f, 10f, 10f) }),
            filled(
                { rect(5f, 9f, 2f, 2f) },
                { rect(5f, 13f, 2f, 2f) },
                { rect(17f, 9f, 2f, 2f) },
                { rect(17f, 13f, 2f, 2f) },
                { rect(9f, 5f, 2f, 2f) },
                { rect(13f, 5f, 2f, 2f) },
                { rect(9f, 17f, 2f, 2f) },
                { rect(13f, 17f, 2f, 2f) },
                { rect(11f, 7f, 2f, 3f) }
            )
        )
    }

    val BugReport by lazy {
        build(
            stroked(
                2.5f,
                { lines(8f, 5f, 5f, 1f) },
                { lines(16f, 5f, 19f, 1f) },
                { rect(7f, 4f, 10f, 3f) },
                { rect(8f, 7f, 8f, 10f) },
                { lines(5f, 9f, 3f, 12f) },
                { lines(19f, 9f, 21f, 12f) },
                { lines(5f, 12f, 3f, 15f) },
                { lines(19f, 12f, 21f, 15f) }
            )
        )
    }

    val LocationOn by lazy {
        build(
            stroked(2.5f, { rect(8f, 3f, 8f, 8f) }),
            filled({ poly(7f, 11f, 17f, 11f, 12f, 19f) }, { rect(10.5f, 6f, 3f, 3f) })
        )
    }

    val BatterySaver by lazy {
        build(
            stroked(2.5f, { rect(3f, 8f, 16f, 8f) }),
            filled(
                { rect(19f, 10f, 2.5f, 4f) },
                { poly(11f, 9f, 8.5f, 12f, 10.5f, 12f, 9.5f, 15f, 13f, 12f, 11f, 12f) }
            )
        )
    }

    val PhoneAndroid by lazy {
        build(
            stroked(2.5f, { rect(6f, 3f, 12f, 18f) }),
            filled({ rect(8f, 5.5f, 8f, 10.5f) }, { rect(10f, 16.5f, 4f, 2.5f) })
        )
    }

    val Info by lazy {
        build(
            stroked(2.5f, { rect(6f, 4f, 12f, 16f) }),
            filled({ rect(10.5f, 7.5f, 3f, 3f) }, { rect(10.5f, 12f, 3f, 5f) })
        )
    }

    val Code by lazy {
        build(
            filled(
                { poly(5f, 12f, 10f, 5f, 13f, 5f, 8f, 12f, 13f, 19f, 10f, 19f) },
                { poly(19f, 12f, 14f, 5f, 11f, 5f, 16f, 12f, 11f, 19f, 14f, 19f) }
            )
        )
    }

    val Notifications by lazy {
        build(
            stroked(2.5f, { poly(7f, 8f, 7f, 6f, 17f, 6f, 17f, 8f, 18f, 13f, 6f, 13f) }),
            filled({ rect(6f, 13f, 12f, 2f) }, { rect(10.5f, 16f, 3f, 2.5f) })
        )
    }
}