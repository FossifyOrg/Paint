package org.fossify.paint.helpers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.PointF
import org.fossify.paint.models.PaintOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.hypot

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DrawingRegionsTest {
    @Test
    fun blackDotDisappearsIntoBlackFillAcrossZoomAndFractionalPan() {
        val enclosure = Path().apply { addRect(-1000f, -1000f, 1000f, 1000f, Path.Direction.CW) }
        val dot = Path().apply { moveTo(0f, 0f); lineTo(0f, 0f) }
        val operations = mutableListOf(
            enclosure to PaintOptions(color = Color.BLUE, strokeWidth = 105f),
            dot to PaintOptions(strokeWidth = 105f)
        )
        val fill = BucketFill(Color.WHITE, operations).fill(200f, 0f, Color.BLACK)!!
        operations.add(fill to PaintOptions(color = Color.BLACK, isFill = true))
        val regions = DrawingRegions(Color.WHITE, operations)
        for ((scale, pan) in viewports()) {
            val bitmap = render(regions, scale, pan, 52.5f)
            assertBlackPatch(bitmap)
            bitmap.recycle()
        }
    }

    @Test
    fun adjoiningColorsHaveNoBackgroundFringeAcrossBrushSizes() {
        val stroke = Path().apply { addCircle(0f, 0f, 80f, Path.Direction.CW) }
        val color = Color.rgb(25, 118, 210)
        for (width in listOf(0.2f, 6f, 105f)) {
            val operations = mutableListOf(stroke to PaintOptions(strokeWidth = width))
            val fill = BucketFill(Color.WHITE, operations).fill(0f, 0f, color)!!
            operations.add(fill to PaintOptions(color = color, isFill = true))
            val regions = DrawingRegions(Color.WHITE, operations)
            for ((scale, pan) in viewports()) {
                val bitmap = render(regions, scale, pan, 80f)
                assertNoBackgroundFringe(bitmap, scale, pan, width)
                if (width * scale >= 2f) {
                    val strokePixel = bitmap.getPixel(128, 128)
                    assertTrue("stroke width=$width scale=$scale pan=$pan color=$strokePixel",
                        maxOf(Color.red(strokePixel), Color.green(strokePixel), Color.blue(strokePixel)) <= 8)
                }
                bitmap.recycle()
            }
        }
    }

    private fun viewports() = listOf(0.1f, 1f, 2.3f, 10f).flatMap { scale ->
        listOf(0f, 0.25f, 0.5f, 0.75f).map { pan -> scale to pan }
    }

    private fun assertBlackPatch(bitmap: Bitmap) {
        for (y in 64 until 192) for (x in 64 until 192) {
            assertEquals("pixel=$x,$y", Color.BLACK, bitmap.getPixel(x, y))
        }
    }

    private fun assertNoBackgroundFringe(bitmap: Bitmap, scale: Float, pan: Float, width: Float) {
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val worldX = (x + 0.5 - 128 - pan) / scale + 80
            val worldY = (y + 0.5 - 128 - pan) / scale
            if (hypot(worldX, worldY) < 80 + width / 2 - 1 / scale) {
                val pixel = bitmap.getPixel(x, y)
                assertTrue("width=$width scale=$scale pan=$pan pixel=$x,$y color=$pixel",
                    Color.red(pixel) <= 27 && Color.green(pixel) <= 120 && Color.blue(pixel) <= 212)
            }
        }
    }

    private fun render(regions: DrawingRegions, scale: Float, pan: Float, centerX: Float = 0f): Bitmap {
        return Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).apply {
                drawColor(Color.WHITE)
                translate(128f + pan, 128f + pan)
                scale(scale, scale)
                translate(-centerX, 0f)
                regions.draw(this, null, PointF(), Color.WHITE)
            }
        }
    }
}
