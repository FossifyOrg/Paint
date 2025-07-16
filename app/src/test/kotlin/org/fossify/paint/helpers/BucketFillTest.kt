package org.fossify.paint.helpers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import org.fossify.paint.models.PaintOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import kotlin.math.hypot

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BucketFillTest {
    @Test
    fun blankAndTransparentCanvasesFillBeyondAnyViewport() {
        val transparent = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        for (image in listOf(null, transparent)) {
            val filler = BucketFill(Color.WHITE, emptyList(), image)
            assertNull(filler.fill(5f, 5f, Color.WHITE))
            for (seed in listOf(PointF(5f, 5f), PointF(-100000f, 200000f))) {
                val fill = filler.fill(seed.x, seed.y, Color.RED)!!
                assertTrue(fill.isInverseFill)
                assertEquals(Color.RED, sample(fill, 5f, 5f))
                assertEquals(Color.RED, sample(fill, -500000f, -500000f))
            }
        }
        transparent.recycle()
    }

    @Test
    fun enclosedFillStopsAtStrokeEdgesAcrossSizesAndCoordinates() {
        for ((bounds, width) in listOf(
            RectF(20f, 20f, 80f, 80f) to 4f,
            RectF(-3000f, -2000f, 4000f, 5000f) to 4f,
            RectF(20f, 20f, 80f, 80f) to 0.2f
        )) {
            val fill = filler(box(bounds.left, bounds.top, bounds.right, bounds.bottom), width)
                .fill(bounds.centerX(), bounds.centerY(), Color.RED)!!
            assertFalse(fill.isInverseFill)
            assertEquals(Color.RED, sample(fill, bounds.centerX(), bounds.centerY()))
            assertEquals(Color.RED, sample(fill, bounds.left + width, bounds.centerY()))
            assertEquals(Color.RED, sample(fill, bounds.right - width, bounds.bottom - width))
            assertEquals(Color.WHITE, sample(fill, bounds.left + width / 4, bounds.centerY()))
            assertEquals(Color.WHITE, sample(fill, bounds.right + width, bounds.centerY()))
        }
    }

    @Test
    fun thinStrokesOutlinesAndDotsCanBeRecoloredWithoutFillingTheirSurroundings() {
        val strokes = listOf(
            box(20f, 20f, 80f, 80f),
            Path().apply { moveTo(10f, 50f); lineTo(90f, 50f) },
            Path().apply { moveTo(20f, 50f); lineTo(20f, 50f) }
        )
        for (stroke in strokes) for (width in listOf(0.2f, 4f)) {
            val fill = filler(stroke, width).fill(20f, 50f, Color.RED)!!
            assertFalse(fill.isInverseFill)
            assertEquals(Color.RED, sample(fill, 20f, 50f))
            assertEquals(Color.WHITE, sample(fill, 50f, 40f))
            assertEquals(Color.WHITE, sample(fill, 10f, 10f))
        }
    }

    @Test
    fun nestedHolesStayHolesWithoutFillingDisconnectedIslands() {
        val operations = listOf(
            box(0f, 0f, 100f, 100f) to PaintOptions(strokeWidth = 4f),
            box(20f, 20f, 80f, 80f) to PaintOptions(strokeWidth = 4f),
            box(40f, 40f, 60f, 60f) to PaintOptions(strokeWidth = 4f)
        )
        val fill = BucketFill(Color.WHITE, operations).fill(10f, 10f, Color.RED)!!
        assertEquals(Color.RED, sample(fill, 10f, 10f))
        assertEquals(Color.WHITE, sample(fill, 30f, 30f))
        assertEquals(Color.WHITE, sample(fill, 50f, 50f))
        val inner = BucketFill(Color.WHITE, operations).fill(50f, 50f, Color.BLUE)!!
        assertEquals(Color.BLUE, sample(inner, 50f, 50f, Color.BLUE))
        assertEquals(Color.WHITE, sample(inner, 30f, 30f, Color.BLUE))
    }

    @Test
    fun erasedGapConnectsInteriorToTheInfiniteOutside() {
        val gap = Path().apply { moveTo(15f, 50f); lineTo(25f, 50f) }
        val operations = listOf(
            box(20f, 20f, 80f, 80f) to PaintOptions(strokeWidth = 4f),
            gap to PaintOptions(strokeWidth = 8f, isEraser = true)
        )
        val fill = BucketFill(Color.WHITE, operations).fill(50f, 50f, Color.RED)!!
        assertTrue(fill.isInverseFill)
        assertEquals(Color.RED, sample(fill, 50f, 50f))
        assertEquals(Color.RED, sample(fill, -100f, -100f))
        assertEquals(Color.WHITE, sample(fill, 50f, 20f))
    }

    @Test
    fun laterStrokesCanSplitAnEarlierFill() {
        val outline = box(20f, 20f, 80f, 80f)
        val fill = filler(outline).fill(30f, 30f, Color.RED)!!
        val divider = Path().apply { moveTo(20f, 50f); lineTo(80f, 50f) }
        val operations = listOf(
            outline to PaintOptions(strokeWidth = 4f),
            fill to PaintOptions(color = Color.RED, isFill = true),
            divider to PaintOptions(strokeWidth = 4f)
        )
        val recolored = BucketFill(Color.WHITE, operations).fill(30f, 30f, Color.BLUE)!!
        assertEquals(Color.BLUE, sample(recolored, 30f, 30f, Color.BLUE))
        assertEquals(Color.WHITE, sample(recolored, 30f, 70f, Color.BLUE))
    }

    @Test
    fun outsideFillCanBeRecoloredAndLaterEnclosed() {
        val outline = box(20f, 20f, 80f, 80f)
        val outside = filler(outline).fill(10f, 10f, Color.RED)!!
        val operations = listOf(
            outline to PaintOptions(strokeWidth = 4f),
            outside to PaintOptions(color = Color.RED, isFill = true),
            box(100f, 100f, 200f, 200f) to PaintOptions(strokeWidth = 4f)
        )
        val insideNewBox = BucketFill(Color.WHITE, operations).fill(150f, 150f, Color.BLUE)!!
        assertFalse(insideNewBox.isInverseFill)
        assertEquals(Color.BLUE, sample(insideNewBox, 150f, 150f, Color.BLUE))
        assertEquals(Color.WHITE, sample(insideNewBox, -100f, -100f, Color.BLUE))
        val recoloredOutside = BucketFill(Color.WHITE, operations).fill(10f, 10f, Color.BLUE)!!
        assertTrue(recoloredOutside.isInverseFill)
        assertEquals(Color.WHITE, sample(recoloredOutside, 150f, 150f, Color.BLUE))
        assertEquals(Color.WHITE, sample(recoloredOutside, 50f, 50f, Color.BLUE))
    }

    @Test
    fun curvedBoundaryRetainsItsEnclosedRegion() {
        val circle = Path().apply { addCircle(50f, 50f, 30f, Path.Direction.CW) }
        val fill = filler(circle).fill(50f, 50f, Color.RED)!!
        assertEquals(Color.RED, sample(fill, 50f, 50f))
        assertEquals(Color.RED, sample(fill, 65f, 65f))
        assertEquals(Color.WHITE, sample(fill, 75f, 75f))
    }

    @Test
    fun importedBitmapAndVectorPathsShareTheSameFillRegion() {
        val image = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        Canvas(image).apply {
            drawColor(Color.WHITE)
            drawRect(20f, 20f, 80f, 80f, Paint().apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 4f
            })
        }
        val divider = Path().apply { moveTo(-30f, 0f); lineTo(30f, 0f) }
        val operations = listOf(divider to PaintOptions(strokeWidth = 4f))
        val filler = BucketFill(Color.WHITE, operations, image, PointF(-50f, -50f))
        val inside = filler.fill(0f, -10f, Color.RED)!!
        assertFalse(inside.isInverseFill)
        assertEquals(Color.RED, sample(inside, 0f, -10f))
        assertEquals(Color.WHITE, sample(inside, 0f, 10f))
        assertEquals(Color.WHITE, sample(inside, 50f, 50f))
        val outside = filler.fill(500f, 500f, Color.RED)!!
        assertTrue(outside.isInverseFill)
        assertEquals(Color.WHITE, sample(outside, 0f, -10f))
        assertEquals(Color.WHITE, sample(outside, 0f, 10f))
        assertEquals(Color.RED, sample(outside, 5000f, 5000f))
    }

    @Test
    fun jpegCompressionNoiseDoesNotLeaveWhiteSpecklesInTheFilledRegion() {
        val source = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        Canvas(source).apply {
            drawColor(Color.WHITE)
            val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            drawCircle(128f, 128f, 95f, pen)
            drawLine(60f, 50f, 195f, 195f, pen)
        }
        val encoded = ByteArrayOutputStream().also {
            source.compress(Bitmap.CompressFormat.JPEG, 80, it)
        }.toByteArray()
        val image = BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
        val fill = BucketFill(Color.WHITE, emptyList(), image).fill(80f, 150f, Color.RED)!!
        val result = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        Canvas(result).apply {
            drawBitmap(image, 0f, 0f, null)
            drawPath(fill, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED })
        }
        for (y in 30 until 225) for (x in 30 until 225) {
            val distanceFromDivider = (145.0 * x - 135.0 * y - 1950.0) / hypot(145.0, 135.0)
            if (hypot(x - 128.0, y - 128.0) < 92 && distanceFromDivider < -2) {
                assertTrue("pixel=$x,$y", Color.green(result.getPixel(x, y)) <= 128)
            }
        }
        assertEquals(image.getPixel(170, 80), result.getPixel(170, 80))
        assertEquals(image.getPixel(128, 123), result.getPixel(128, 123))
        source.recycle()
        image.recycle()
        result.recycle()
    }

    @Test
    fun regionsConnectThroughOverlapButNotThroughOneCorner() {
        for ((edge, expected) in listOf(40f to Color.WHITE, 45f to Color.RED)) {
            val operations = listOf(
                box(20f, 20f, edge, edge) to PaintOptions(color = Color.BLACK, isFill = true),
                box(40f, 40f, 60f, 60f) to PaintOptions(color = Color.BLACK, isFill = true)
            )
            val fill = BucketFill(Color.WHITE, operations).fill(30f, 30f, Color.RED)!!
            assertEquals(Color.RED, sample(fill, 30f, 30f))
            assertEquals(expected, sample(fill, 50f, 50f))
        }
    }

    @Test
    fun bitmapFillsRespectHolesAndEdgeConnectivity() {
        assertBitmapFill(listOf("#.", ".#"), PointF(0.5f, 0.5f), listOf("#.", ".."))
        assertBitmapFill(listOf("##.#", ".#.#"), PointF(0.5f, 0.5f), listOf("##..", ".#.."))
        assertBitmapFill(listOf("###", "#.#", "###"), PointF(0.5f, 0.5f), listOf("###", "#.#", "###"))
        assertBitmapFill(listOf("###", "#.#", "###"), PointF(1.5f, 1.5f), listOf("...", ".#.", "..."))
    }

    @Test
    fun complexBoundariesKeepRegionsSeparateAfterRepeatedRecoloring() {
        val strip = Path().apply { addRect(-200f, 40f, 200f, 60f, Path.Direction.CW) }
        val count = 5000
        val boundary = Path()
        boundary.moveTo(100f, 0f)
        for (i in 1 until count) {
            val angle = 2 * Math.PI * i / count
            val radius = if (i % 2 == 0) 100 else 95
            boundary.lineTo(
                (radius * kotlin.math.cos(angle)).toFloat(), (radius * kotlin.math.sin(angle)).toFloat()
            )
        }
        boundary.close()
        val operations = mutableListOf(
            strip to PaintOptions(color = Color.BLUE, isFill = true),
            boundary to PaintOptions(color = Color.BLACK, isFill = true)
        )
        val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        repeat(3) { iteration ->
            val color = if (iteration % 2 == 0) Color.RED else Color.GREEN
            val fill = BucketFill(Color.WHITE, operations).fill(-150f, 50f, color)!!
            operations.add(fill to PaintOptions(color = color, isFill = true))
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                translate(200f, 100f)
                drawPath(fill, Paint().apply { this.color = color })
            }
            assertEquals(color, bitmap.getPixel(50, 150))
            assertEquals(color, bitmap.getPixel(108, 150))
            assertEquals(Color.WHITE, bitmap.getPixel(120, 150))
            assertEquals(Color.WHITE, bitmap.getPixel(350, 150))
        }
        bitmap.recycle()
    }

    private fun assertBitmapFill(pixels: List<String>, seed: PointF, expected: List<String>) {
        val image = Bitmap.createBitmap(pixels.first().length, pixels.size, Bitmap.Config.ARGB_8888)
        for (y in pixels.indices) for (x in pixels[y].indices) {
            image.setPixel(x, y, if (pixels[y][x] == '#') Color.BLACK else Color.WHITE)
        }
        val fill = BucketFill(Color.WHITE, emptyList(), image).fill(seed.x, seed.y, Color.RED)!!
        assertFalse(fill.isInverseFill)
        for (y in expected.indices) for (x in expected[y].indices) {
            assertEquals("pixel=$x,$y", if (expected[y][x] == '#') Color.RED else Color.WHITE,
                sample(fill, x + 0.5f, y + 0.5f))
        }
        image.recycle()
    }

    private fun filler(path: Path, width: Float = 4f) =
        BucketFill(Color.WHITE, listOf(path to PaintOptions(strokeWidth = width)))

    private fun box(left: Float, top: Float, right: Float, bottom: Float) =
        Path().apply { addRect(RectF(left, top, right, bottom), Path.Direction.CW) }

    private fun sample(path: Path, x: Float, y: Float, color: Int = Color.RED): Int {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            translate(0.5f - x, 0.5f - y)
            drawPath(path, Paint().apply { this.color = color; style = Paint.Style.FILL; isAntiAlias = false })
        }
        return bitmap.getPixel(0, 0).also { bitmap.recycle() }
    }
}
