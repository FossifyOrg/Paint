package org.fossify.paint.helpers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import com.github.micycle1.clipper2.core.Paths64
import com.github.micycle1.clipper2.core.Point64
import org.fossify.paint.models.MyPath
import org.fossify.paint.models.PaintOptions
import kotlin.math.abs

private const val PIXEL_CENTER = 0.5f
private const val BITMAP_COLOR_TOLERANCE = 24

class BucketFill(
    private val backgroundColor: Int,
    private val operations: List<Pair<Path, PaintOptions>>,
    private val backgroundBitmap: Bitmap? = null,
    private val bitmapOrigin: PointF = PointF()
) {
    fun fill(x: Float, y: Float, color: Int): MyPath? {
        val targetColor = sampleColor(x, y)
        if (targetColor == color) return null

        var region = bitmapRegion(targetColor)
        val pending = Paths64()
        var pendingAdd = false
        fun flush() {
            if (pending.isNotEmpty()) {
                region = region.paint(pending, false, pendingAdd)
                pending.clear()
            }
        }
        val paint = Paint()
        for ((path, options) in operations) {
            options.applyTo(paint, backgroundColor)
            val shape = operationShape(path, options, paint)
            val outline = FillRegion.fromPath(shape).paths
            val add = sameColor(targetColor, paint.color)
            if (shape.isInverseFillType) {
                flush()
                region = region.paint(outline, true, add)
            } else {
                if (pendingAdd != add) flush()
                pendingAdd = add
                pending.addAll(outline)
            }
        }
        flush()
        return region.connectedAt(Point64(x * COORDINATE_SCALE, y * COORDINATE_SCALE))
    }

    private fun sampleColor(x: Float, y: Float): Int {
        val bitmap = createBitmap(1, 1)
        val canvas = Canvas(bitmap)
        canvas.drawColor(backgroundColor)
        canvas.translate(PIXEL_CENTER - x, PIXEL_CENTER - y)
        backgroundBitmap?.let { canvas.drawBitmap(it, bitmapOrigin.x, bitmapOrigin.y, null) }
        val paint = Paint().apply { isAntiAlias = false }
        for ((path, options) in operations) {
            options.applyTo(paint, backgroundColor)
            val shape = operationShape(path, options, paint)
            paint.style = Paint.Style.FILL
            canvas.drawPath(shape, paint)
        }
        val color = bitmap[0, 0]
        bitmap.recycle()
        return color
    }

    private fun operationShape(path: Path, options: PaintOptions, paint: Paint) = Path().apply {
        if (options.isFill) set(path) else paint.getFillPath(path, this)
    }

    private fun bitmapRegion(targetColor: Int): FillRegion {
        val image = backgroundBitmap ?: return FillRegion(Paths64(), sameColor(targetColor, backgroundColor))
        val inverse = sameColor(targetColor, backgroundColor, BITMAP_COLOR_TOLERANCE)
        val bitmap = createBitmap(image.width, image.height)
        Canvas(bitmap).apply {
            drawColor(backgroundColor)
            drawBitmap(image, 0f, 0f, null)
        }
        val pixels = IntArray(image.width * image.height)
        bitmap.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        bitmap.recycle()

        val included = BooleanArray(pixels.size) {
            sameColor(pixels[it], targetColor, BITMAP_COLOR_TOLERANCE) != inverse
        }
        val edges = bitmapEdges(included, image.width, image.height)
        val outline = bitmapOutline(edges, image.width + 1, bitmapOrigin, inverse)
        return FillRegion(polygonPaths(outline), inverse)
    }

    private fun sameColor(first: Int, second: Int, tolerance: Int = 1) =
        abs(Color.red(first) - Color.red(second)) <= tolerance &&
                abs(Color.green(first) - Color.green(second)) <= tolerance &&
                abs(Color.blue(first) - Color.blue(second)) <= tolerance
}

private const val EDGE_RIGHT = 0
private const val EDGE_DOWN = 1
private const val EDGE_LEFT = 2
private const val EDGE_UP = 3

private fun bitmapEdges(included: BooleanArray, width: Int, height: Int): ByteArray {
    val stride = width + 1
    val edges = ByteArray(stride * (height + 1))
    fun add(vertex: Int, direction: Int) {
        edges[vertex] = (edges[vertex].toInt() or (1 shl direction)).toByte()
    }
    for (y in 0 until height) {
        for (x in 0 until width) {
            val pixel = y * width + x
            if (!included[pixel]) continue
            val vertex = y * stride + x
            if (y == 0 || !included[pixel - width]) add(vertex, EDGE_RIGHT)
            if (x == width - 1 || !included[pixel + 1]) add(vertex + 1, EDGE_DOWN)
            if (y == height - 1 || !included[pixel + width]) add(vertex + stride + 1, EDGE_LEFT)
            if (x == 0 || !included[pixel - 1]) add(vertex + stride, EDGE_UP)
        }
    }
    return edges
}

private fun bitmapOutline(edges: ByteArray, stride: Int, origin: PointF, inverse: Boolean): Path {
    val outline = Path()
    val steps = intArrayOf(1, stride, -1, -stride)
    val turn = if (inverse) -1 else 1
    for (start in edges.indices) {
        while (edges[start].toInt() != 0) {
            traceBitmapContour(outline, edges, steps, origin, start, turn)
        }
    }
    return outline
}

private fun traceBitmapContour(
    outline: Path, edges: ByteArray, steps: IntArray, origin: PointF, start: Int, turn: Int
) {
    val stride = steps[EDGE_DOWN]
    var vertex = start
    var direction = Integer.numberOfTrailingZeros(edges[start].toInt())
    outline.moveTo(origin.x + vertex % stride, origin.y + vertex / stride)
    do {
        edges[vertex] = (edges[vertex].toInt() and (1 shl direction).inv()).toByte()
        val next = vertex + steps[direction]
        if (next == start) {
            outline.close()
            break
        }
        val outgoing = edges[next].toInt()
        check(outgoing != 0) { "Open bitmap fill contour at vertex $next" }
        var nextDirection = (direction + turn + steps.size) % steps.size
        while (outgoing and (1 shl nextDirection) == 0) {
            nextDirection = (nextDirection - turn + steps.size) % steps.size
        }
        if (nextDirection != direction) outline.lineTo(origin.x + next % stride, origin.y + next / stride)
        vertex = next
        direction = nextDirection
    } while (true)
}
