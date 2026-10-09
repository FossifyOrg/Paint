package org.fossify.paint.helpers

import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import com.github.micycle1.clipper2.Clipper
import com.github.micycle1.clipper2.core.ClipType
import com.github.micycle1.clipper2.core.FillRule
import com.github.micycle1.clipper2.core.Path64
import com.github.micycle1.clipper2.core.Paths64
import com.github.micycle1.clipper2.core.Point64
import com.github.micycle1.clipper2.engine.Clipper64
import com.github.micycle1.clipper2.engine.PointInPolygonResult
import com.github.micycle1.clipper2.engine.PolyPath64
import com.github.micycle1.clipper2.engine.PolyTree64
import org.fossify.paint.models.MyPath
import org.fossify.paint.models.PaintOptions

private const val CURVE_APPROXIMATION_ERROR = 0.025f
private const val APPROXIMATION_POINT_SIZE = 3
internal const val COORDINATE_SCALE = 1000.0

internal class FillRegion(val paths: Paths64, val inverse: Boolean) {
    fun paint(outline: Paths64, outlineInverse: Boolean, add: Boolean): FillRegion {
        val result = when {
            !outlineInverse -> clip(if (add != inverse) ClipType.Union else ClipType.Difference, paths, outline)
            inverse == add -> clip(ClipType.Intersection, paths, outline)
            else -> clip(ClipType.Difference, outline, paths)
        }
        return FillRegion(result, if (outlineInverse) add else inverse)
    }

    fun toPath() = MyPath().apply {
        isInverseFill = inverse
        for (contour in paths) addContour(contour)
    }

    fun connectedAt(point: Point64): MyPath? {
        val tree = PolyTree64()
        val engine = Clipper64()
        if (inverse) {
            val frame = Clipper.getBounds(paths).apply { left--; top--; right++; bottom++ }
            engine.addSubject(frame.asPath())
            engine.addClip(paths)
        } else {
            engine.addSubject(paths)
        }
        check(engine.execute(if (inverse) ClipType.Difference else ClipType.Union, FillRule.NonZero, tree)) {
            "Failed to resolve fill component: ${paths.size} contours, inverse=$inverse"
        }
        val outside = if (inverse) tree[0] else null
        var containing: PolyPath64 = outside ?: tree
        var inside = inverse
        while (true) {
            val child = (0 until containing.count).asSequence().map { containing[it] }.firstOrNull {
                Clipper.pointInPolygon(point, it.polygon) != PointInPolygonResult.IsOutside
            } ?: break
            containing = child
            inside = !inside
        }
        if (!inside) return null

        return MyPath().apply {
            isInverseFill = containing === outside
            // The temporary outer frame finds connected regions; it never bounds the saved fill.
            if (!isInverseFill) addContour(containing.polygon)
            for (index in 0 until containing.count) addContour(containing[index].polygon)
        }
    }

    companion object {
        fun fromPath(path: Path): FillRegion {
            val rule = when (path.fillType) {
                Path.FillType.EVEN_ODD, Path.FillType.INVERSE_EVEN_ODD -> FillRule.EvenOdd
                else -> FillRule.NonZero
            }
            return FillRegion(clip(ClipType.Union, polygonPaths(path), Paths64(), rule), path.isInverseFillType)
        }

        fun fromOperation(path: Path, options: PaintOptions, backgroundColor: Int): FillRegion {
            val paint = Paint().apply { options.applyTo(this, backgroundColor) }
            val shape = Path().apply { if (options.isFill) set(path) else paint.getFillPath(path, this) }
            return fromPath(shape)
        }
    }
}

private fun clip(type: ClipType, subject: Paths64, clip: Paths64, rule: FillRule = FillRule.NonZero): Paths64 {
    val engine = Clipper64()
    engine.addSubject(subject)
    engine.addClip(clip)
    return Paths64().also {
        check(engine.execute(type, rule, it)) {
            "Fill clipping failed: $type, $rule, ${subject.size} subject and ${clip.size} clip contours"
        }
    }
}

internal fun polygonPaths(path: Path): Paths64 {
    val result = Paths64()
    val measure = PathMeasure(path, true)
    do {
        val contour = Path()
        if (!measure.getSegment(0f, measure.length, contour, true)) continue
        val approximation = contour.approximate(CURVE_APPROXIMATION_ERROR)
        val points = Path64()
        for (i in approximation.indices step APPROXIMATION_POINT_SIZE) {
            val point = Point64(approximation[i + 1] * COORDINATE_SCALE, approximation[i + 2] * COORDINATE_SCALE)
            if (points.lastOrNull() != point) points.add(point)
        }
        result.add(points)
    } while (measure.nextContour())
    return result
}

private fun MyPath.addContour(points: Path64) {
    moveTo((points[0].x / COORDINATE_SCALE).toFloat(), (points[0].y / COORDINATE_SCALE).toFloat())
    for (point in points.drop(1)) lineTo((point.x / COORDINATE_SCALE).toFloat(), (point.y / COORDINATE_SCALE).toFloat())
    close()
}
