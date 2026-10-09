package org.fossify.paint.helpers

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import com.github.micycle1.clipper2.core.Paths64
import org.fossify.paint.models.MyPath
import org.fossify.paint.models.PaintOptions

internal class DrawingRegions(
    backgroundColor: Int,
    operations: List<Pair<Path, PaintOptions>>,
    bitmapBounds: RectF? = null,
    previous: DrawingRegions? = null
) {
    private val regions: Map<Int?, FillRegion>
    val paths: Map<Int?, MyPath>

    init {
        val regions = previous?.regions?.toMutableMap()
            ?: linkedMapOf<Int?, FillRegion>(backgroundColor to FillRegion(Paths64(), true))
        val pending = Paths64()
        var pendingColor = backgroundColor
        fun apply(outline: FillRegion, color: Int?) {
            regions.putIfAbsent(color, FillRegion(Paths64(), false))
            for (entry in regions.entries) {
                entry.setValue(entry.value.paint(outline.paths, outline.inverse, entry.key == color))
            }
        }
        fun flush() {
            if (pending.isNotEmpty()) apply(FillRegion(pending, false), pendingColor)
            pending.clear()
        }
        if (previous == null && bitmapBounds != null) {
            val bounds = Path().apply { addRect(bitmapBounds, Path.Direction.CW) }
            apply(FillRegion.fromPath(bounds), null)
        }
        for ((path, options) in operations) {
            val outline = FillRegion.fromOperation(path, options, backgroundColor)
            val color = if (options.isEraser) backgroundColor else options.color
            if (outline.inverse) {
                flush()
                apply(outline, color)
            } else {
                if (pendingColor != color) flush()
                pendingColor = color
                pending.addAll(outline.paths)
            }
        }
        flush()
        this.regions = regions
        paths = regions.filterValues { it.inverse || it.paths.isNotEmpty() }.mapValues { it.value.toPath() }
    }

    fun draw(canvas: Canvas, bitmap: Bitmap?, origin: PointF, backgroundColor: Int) {
        val layer = canvas.saveLayer(null, OPAQUE_COVERAGE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = ADD_COVERAGE }
        for ((color, path) in paths) {
            paint.color = color ?: backgroundColor
            paint.shader = if (color == null && bitmap != null) {
                BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    setLocalMatrix(Matrix().apply { setTranslate(origin.x, origin.y) })
                }
            } else null
            canvas.drawPath(path, paint)
        }
        canvas.restoreToCount(layer)
    }

    companion object {
        private val ADD_COVERAGE = PorterDuffXfermode(PorterDuff.Mode.ADD)
        // Normalize shared edge coverage before compositing the opaque canvas.
        private val OPAQUE_COVERAGE = Paint().apply {
            colorFilter = ColorMatrixColorFilter(floatArrayOf(
                1f, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1f, 0f, 0f,
                0f, 0f, 0f, 0f, 255f
            ))
        }
    }
}

internal class DrawingRenderer {
    data class Snapshot(
        val operations: List<Pair<MyPath, PaintOptions>>,
        val backgroundColor: Int,
        val bitmap: Bitmap?,
        val origin: PointF
    ) {
        val fillEnd = operations.indexOfLast { it.second.isFill } + 1

        fun hasSameBackground(other: Snapshot) = backgroundColor == other.backgroundColor &&
            bitmap === other.bitmap && origin == other.origin
    }

    data class Drawing(
        val snapshot: Snapshot,
        val regions: DrawingRegions? = null,
        val opaqueBitmap: Bitmap? = null
    )

    var drawing = Drawing(Snapshot(emptyList(), Color.WHITE, null, PointF()))
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun update(snapshot: Snapshot): Boolean {
        val previous = drawing.snapshot
        when {
            previous == snapshot -> Unit
            snapshot.fillEnd == 0 -> drawing = Drawing(snapshot)
            snapshot.hasSameBackground(previous) && snapshot.fillEnd == previous.fillEnd &&
                snapshot.operations.take(snapshot.fillEnd) == previous.operations.take(previous.fillEnd) -> {
                drawing = drawing.copy(snapshot = snapshot)
            }
            else -> return false
        }
        return true
    }

    fun draw(canvas: Canvas) {
        val snapshot = drawing.snapshot
        canvas.drawColor(snapshot.backgroundColor)
        val regions = drawing.regions
        if (regions != null) {
            regions.draw(canvas, drawing.opaqueBitmap, snapshot.origin, snapshot.backgroundColor)
        } else {
            snapshot.bitmap?.let { canvas.drawBitmap(it, snapshot.origin.x, snapshot.origin.y, null) }
        }
        val start = if (regions == null) 0 else snapshot.fillEnd
        for (index in start until snapshot.operations.size) {
            val (path, options) = snapshot.operations[index]
            options.applyTo(paint, snapshot.backgroundColor)
            canvas.drawPath(path, paint)
        }
    }

    companion object {
        fun prepare(snapshot: Snapshot, previous: Drawing): Drawing {
            if (snapshot.fillEnd == 0) return Drawing(snapshot)
            val bitmap = snapshot.bitmap
            val origin = snapshot.origin
            val opaqueBitmap = if (previous.snapshot.bitmap === bitmap &&
                previous.snapshot.backgroundColor == snapshot.backgroundColor && previous.opaqueBitmap != null) {
                previous.opaqueBitmap
            } else {
                bitmap?.let { image ->
                    createBitmap(image.width, image.height).also {
                        val canvas = Canvas(it)
                        canvas.drawColor(snapshot.backgroundColor)
                        canvas.drawBitmap(image, 0f, 0f, null)
                    }
                }
            }
            val bounds = bitmap?.let { RectF(origin.x, origin.y, origin.x + it.width, origin.y + it.height) }
            val prefix = snapshot.operations.take(snapshot.fillEnd)
            val previousPrefix = previous.snapshot.operations.take(previous.snapshot.fillEnd)
            val canExtend = previous.regions != null && snapshot.hasSameBackground(previous.snapshot) &&
                prefix.size >= previousPrefix.size && prefix.take(previousPrefix.size) == previousPrefix
            val regions = if (canExtend) {
                DrawingRegions(snapshot.backgroundColor, prefix.drop(previousPrefix.size), bounds, previous.regions)
            } else {
                DrawingRegions(snapshot.backgroundColor, prefix, bounds)
            }
            return Drawing(snapshot, regions, opaqueBitmap)
        }
    }
}
