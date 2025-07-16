package org.fossify.paint.views

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.core.graphics.PathParser
import org.fossify.paint.helpers.BucketFill
import org.fossify.paint.activities.MainActivity
import org.fossify.paint.extensions.config
import org.fossify.paint.helpers.PNG
import org.fossify.paint.helpers.SVG
import org.fossify.paint.models.MyPath
import org.fossify.paint.models.PaintOptions
import org.fossify.paint.models.Svg
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executor
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CanvasFillTest {

    @Test
    fun fillUsesLocalCoordinatesAcrossViewOffsetsZoomAndPan() {
        for (scale in listOf(0.1f, 1f, 10f)) {
            val canvas = canvas()
            canvas.layout(40, 80, 140, 180)
            canvas.addOperation(box(-50f, -50f, 150f, 150f), PaintOptions(strokeWidth = 4f))
            val size = 100f / scale
            canvas.setViewportBounds(RectF(50f - size / 2, 50f - size / 2, 50f + size / 2, 50f + size / 2))
            canvas.toggleBucketFill(true)
            canvas.setColor(Color.RED)
            tap(canvas, 50f, 50f)
            shadowOf(Looper.getMainLooper()).idle()
            canvas.setViewportBounds(RectF(-100f, -100f, 200f, 200f))
            val bitmap = canvas.settledBitmap()
            assertEquals(Color.RED, bitmap.getPixel(50, 50))
            assertEquals(Color.RED, bitmap.getPixel(20, 20))
            assertEquals(Color.WHITE, bitmap.getPixel(5, 5))
        }
    }

    @Test
    fun canceledGestureDoesNotFillOrDestroyRedo() {
        val canvas = canvas()
        val pendingFills = ArrayDeque<Runnable>()
        canvas.drawingExecutor = Executor { pendingFills.addLast(it) }
        canvas.addOperation(box(20f, 20f, 80f, 80f), PaintOptions(strokeWidth = 4f))
        canvas.undo()
        canvas.toggleBucketFill(true)
        event(canvas, MotionEvent.ACTION_DOWN, 50f, 50f)
        event(canvas, MotionEvent.ACTION_CANCEL, 50f, 50f)
        finishFills(pendingFills)
        assertTrue(canvas.getPathsMap().isEmpty())
        canvas.redo()
        assertEquals(1, canvas.getPathsMap().size)
        assertEquals(Color.BLACK, canvas.settledBitmap().getPixel(20, 50))
    }

    @Test
    fun fillsAndBrushStrokesKeepTheirAcceptedOrder() {
        val canvas = canvas()
        val pendingFills = ArrayDeque<Runnable>()
        canvas.drawingExecutor = Executor { pendingFills.addLast(it) }
        canvas.toggleBucketFill(true)
        canvas.setColor(Color.RED)
        tap(canvas, 50f, 10f)
        assertEquals(1, pendingFills.size)
        addStroke(canvas, Color.BLACK, 50f)
        assertEquals(Color.BLACK, canvas.settledBitmap().getPixel(50, 50))
        canvas.toggleBucketFill(true)
        canvas.setColor(Color.BLUE)
        tap(canvas, 50f, 10f)
        finishFills(pendingFills)
        assertEquals(3, canvas.getPathsMap().size)
        assertEquals(Color.BLUE, canvas.settledBitmap().getPixel(50, 10))
        assertEquals(Color.BLACK, canvas.settledBitmap().getPixel(50, 50))
        canvas.undo()
        assertTrue(canvas.hasPendingEdits())
        assertEquals(Color.BLUE, canvas.getBitmap().getPixel(50, 10))
        finishFills(pendingFills)
        assertEquals(Color.RED, canvas.settledBitmap().getPixel(50, 10))
        assertEquals(Color.BLACK, canvas.settledBitmap().getPixel(50, 50))
        canvas.undo()
        finishFills(pendingFills)
        assertEquals(Color.RED, canvas.settledBitmap().getPixel(50, 50))
        canvas.undo()
        finishFills(pendingFills)
        assertEquals(Color.WHITE, canvas.settledBitmap().getPixel(50, 50))
        repeat(3) { canvas.redo() }
        finishFills(pendingFills)
        assertEquals(Color.BLUE, canvas.settledBitmap().getPixel(50, 10))
        assertEquals(Color.BLACK, canvas.settledBitmap().getPixel(50, 50))
    }

    @Test
    fun viewportBelowMinimumZoomStillRespondsToPinchWithoutFilling() {
        val canvas = canvas()
        canvas.layout(0, 0, 500, 1000)
        canvas.setViewportBounds(RectF(0f, 0f, 10000f, 10000f))
        canvas.toggleBucketFill(true)
        canvas.setColor(Color.RED)
        val before = canvas.getViewportBounds().width()
        pinch(canvas)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(canvas.getViewportBounds().width() < before)
        assertTrue(canvas.getPathsMap().isEmpty())
        assertFalse(canvas.hasPendingEdits())
    }

    @Test
    fun invalidSvgViewportLeavesTheExistingDrawingUntouched() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val canvas = activityCanvas(activity)
        canvas.updateBackgroundColor(Color.WHITE)
        val image = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.YELLOW) }
        canvas.mBackgroundBitmap = image
        canvas.addOperation(box(20f, 20f, 80f, 80f), PaintOptions(color = Color.BLUE, isFill = true))
        shadowOf(Looper.getMainLooper()).idle()
        canvas.setViewportBounds(RectF(-100f, -100f, canvas.width - 100f, canvas.height - 100f))
        val viewport = canvas.getViewportBounds()
        val paths = canvas.getPathsMap().mapValues { it.value.copy() }
        val file = File(activity.cacheDir, "invalid-viewport.svg")
        for (bounds in listOf("0 0 0 0", "0 0 -100 100", "0 0 NaN 100", "0 0 Infinity 100", "1e20 0 1 100")) {
            file.writeText("""<svg width="100" height="100" viewBox="$bounds"
                xmlns="http://www.w3.org/2000/svg"><rect width="100" height="100" fill="#ffffff"/></svg>""")
            deliverResult(activity, 1, Intent().setDataAndType(Uri.fromFile(file), "image/svg+xml"))
            shadowOf(Looper.getMainLooper()).idle()
            assertSame(image, canvas.mBackgroundBitmap)
            assertEquals(paths, canvas.getPathsMap())
            assertEquals(viewport, canvas.getViewportBounds())
        }
        file.delete()
    }

    @Test
    fun undoingClearRestoresTheDrawingAtItsPlaceInTheQueue() {
        for (undoFill in listOf(false, true)) {
            val canvas = canvas()
            val pendingFills = ArrayDeque<Runnable>()
            canvas.drawingExecutor = Executor { pendingFills.addLast(it) }
            canvas.addOperation(box(20f, 20f, 80f, 80f), PaintOptions(strokeWidth = 4f))
            canvas.toggleBucketFill(true)
            canvas.setColor(Color.RED)
            tap(canvas, 50f, 30f)
            if (undoFill) canvas.undo() else addStroke(canvas, Color.BLACK, 50f)
            canvas.clearCanvas()
            finishFills(pendingFills)
            assertTrue(canvas.getPathsMap().isEmpty())
            canvas.undo()
            finishFills(pendingFills)
            assertEquals(if (undoFill) 1 else 3, canvas.getPathsMap().size)
            assertEquals(Color.BLACK, canvas.settledBitmap().getPixel(20, 50))
            assertEquals(if (undoFill) Color.WHITE else Color.RED, canvas.settledBitmap().getPixel(50, 30))
            assertEquals(if (undoFill) Color.WHITE else Color.BLACK, canvas.settledBitmap().getPixel(50, 50))
        }
    }

    @Test
    fun queuedClearPreservesTheStrokeStartedAfterIt() {
        val canvas = canvas()
        val pendingFills = ArrayDeque<Runnable>()
        canvas.drawingExecutor = Executor { pendingFills.addLast(it) }
        canvas.toggleBucketFill(true)
        canvas.setColor(Color.RED)
        tap(canvas, 50f, 10f)
        canvas.clearCanvas()
        canvas.toggleBucketFill(false)
        canvas.setColor(Color.BLACK)
        canvas.setBrushSize(10f)
        event(canvas, MotionEvent.ACTION_DOWN, 10f, 50f)
        event(canvas, MotionEvent.ACTION_MOVE, 80f, 50f)
        finishFills(pendingFills)
        event(canvas, MotionEvent.ACTION_UP, 80f, 50f)
        val bitmap = canvas.settledBitmap()
        assertEquals(Color.BLACK, bitmap.getPixel(20, 50))
        assertEquals(Color.BLACK, bitmap.getPixel(70, 50))
        assertEquals(Color.WHITE, bitmap.getPixel(20, 20))
        assertEquals(1, canvas.getPathsMap().size)
    }

    @Test
    fun failedDeferredSavesLeaveTheDrawingUnsavedAndContinueQueuedEdits() {
        for (extension in listOf(SVG, PNG)) {
            DrawingStateHolder.operations = null
            val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            shadowOf(Looper.getMainLooper()).idle()
            val canvas = activityCanvas(activity)
            canvas.clearCanvas()
            canvas.updateBackgroundColor(Color.WHITE)
            canvas.layout(0, 0, 100, 100)
            val pendingFills = ArrayDeque<Runnable>()
            canvas.drawingExecutor = Executor { pendingFills.addLast(it) }
            canvas.toggleBucketFill(true)
            canvas.setColor(Color.RED)
            tap(canvas, 50f, 10f)
            var saved = false
            val output = object : OutputStream() {
                override fun write(value: Int) = Unit
                override fun close() = throw IOException("Storage disconnected")
            }
            val onSaved: (Long) -> Unit = { saved = true }
            if (extension == SVG) {
                Svg.saveToOutputStream(activity, output, canvas, onSaved)
            } else {
                MainActivity::class.java.getDeclaredMethod(
                    "saveToOutputStream", OutputStream::class.java, Bitmap.CompressFormat::class.java,
                    Boolean::class.javaPrimitiveType, Function1::class.java
                ).apply { isAccessible = true }.invoke(activity, output, Bitmap.CompressFormat.PNG, true, onSaved)
            }
            addStroke(canvas, Color.BLUE, 80f)
            finishFills(pendingFills)
            assertFalse(saved)
            assertFalse(activity.isFinishing)
            assertFalse(canvas.hasPendingEdits())
            assertEquals(Color.BLUE, canvas.settledBitmap().getPixel(20, 80))
            val backPress = MainActivity::class.java.getDeclaredMethod("onBackPressedCompat").apply {
                isAccessible = true
            }
            assertTrue(backPress.invoke(activity) as Boolean)
        }
    }

    @Test
    fun successfulSvgAndBitmapSavesDoNotPromptOnBack() {
        for (extension in listOf(SVG, PNG)) {
            RuntimeEnvironment.getApplication().config.lastSaveExtension = extension
            DrawingStateHolder.operations = null
            val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            shadowOf(Looper.getMainLooper()).idle()
            val canvas = activityCanvas(activity)
            canvas.clearCanvas()
            canvas.layout(0, 0, 100, 100)
            canvas.addOperation(box(20f, 20f, 80f, 80f), PaintOptions(strokeWidth = 4f))
            val file = File(activity.cacheDir, "settled-drawing.$extension")
            deliverResult(activity, 2, Intent().setData(Uri.fromFile(file)))
            assertTrue(file.length() > 0)
            val backPress = MainActivity::class.java.getDeclaredMethod("onBackPressedCompat").apply {
                isAccessible = true
            }
            assertFalse("$extension save should allow closing", backPress.invoke(activity) as Boolean)
            assertFalse(canvas.hasPendingEdits())
            canvas.addOperation(box(30f, 30f, 70f, 70f), PaintOptions(strokeWidth = 4f))
            assertTrue("$extension save should leave later edits unsaved", backPress.invoke(activity) as Boolean)
            file.delete()
        }
    }

    @Test
    fun svgAndBitmapSavingWaitForTheAcceptedDrawing() {
        for (extension in listOf(SVG, PNG)) {
            RuntimeEnvironment.getApplication().config.lastSaveExtension = extension
            DrawingStateHolder.operations = null
            val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            shadowOf(Looper.getMainLooper()).idle()
            val canvas = activityCanvas(activity)
            canvas.clearCanvas()
            canvas.updateBackgroundColor(Color.WHITE)
            canvas.layout(0, 0, 100, 100)
            val pendingFills = ArrayDeque<Runnable>()
            canvas.drawingExecutor = Executor { pendingFills.addLast(it) }
            canvas.toggleBucketFill(true)
            canvas.setColor(Color.RED)
            tap(canvas, 50f, 10f)
            addStroke(canvas, Color.BLACK, 50f)
            assertEquals(Color.BLACK, canvas.settledBitmap().getPixel(50, 50))
            val file = File(activity.cacheDir, "pending-drawing.$extension")
            deliverResult(activity, 2, Intent().setData(Uri.fromFile(file)))
            assertEquals(0L, file.length())
            val backPress = MainActivity::class.java.getDeclaredMethod("onBackPressedCompat").apply {
                isAccessible = true
            }
            assertTrue(canvas.hasPendingEdits())
            addStroke(canvas, Color.BLUE, 80f)
            finishFills(pendingFills)
            assertEquals(Color.BLUE, canvas.settledBitmap().getPixel(20, 80))
            assertFalse(canvas.hasPendingEdits())
            assertTrue(backPress.invoke(activity) as Boolean)
            if (extension == SVG) {
                val saved = file.inputStream().use { Svg.parseSvg(it) }
                assertEquals(2, saved.paths.size)
                assertTrue(saved.paths.values.first().isFill)
                assertEquals(Color.BLACK, saved.paths.values.last().color)
            } else {
                val saved = BitmapFactory.decodeFile(file.path)
                assertEquals(Color.RED, saved.getPixel(50, 10))
                assertEquals(Color.BLACK, saved.getPixel(50, 50))
                assertEquals(Color.RED, saved.getPixel(20, 80))
                saved.recycle()
            }
            file.delete()
        }
    }

    @Test
    fun svgSaveAndLoadPreserveFiniteAndInfiniteFillsWithHolesAndViewport() {
        for (inverse in listOf(false, true)) {
            DrawingStateHolder.operations = null
            val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            shadowOf(Looper.getMainLooper()).idle()
            val canvas = activityCanvas(activity)
            canvas.clearCanvas()
            canvas.updateBackgroundColor(Color.WHITE)
            canvas.addOperation(box(-50f, -50f, 150f, 150f), PaintOptions(strokeWidth = 4f))
            canvas.addOperation(box(20f, 20f, 80f, 80f), PaintOptions(strokeWidth = 4f))
            val seed = if (inverse) -90f else 0f
            val fill = BucketFill(Color.WHITE, canvas.getPathsMap().map { (path, options) -> Path(path) to options })
                .fill(seed, seed, Color.RED)!!
            canvas.addOperation(fill, PaintOptions(color = Color.RED, isFill = true))
            canvas.setViewportBounds(RectF(-100f, -100f, canvas.width - 100f, canvas.height - 100f))
            val before = canvas.settledBitmap()
            val viewport = canvas.getViewportBounds()
            val file = File(activity.cacheDir, "fill-roundtrip.svg")
            Svg.saveToOutputStream(activity, FileOutputStream(file), canvas)
            canvas.clearCanvas()
            canvas.setViewportBounds(RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat()))
            Svg.loadSvg(activity, file, canvas)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(viewport, canvas.getViewportBounds())
            assertEquals(inverse, canvas.getPathsMap().keys.last().isInverseFill)
            assertBitmapEquals(before, canvas.settledBitmap())

            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val visiblePaths = XPathFactory.newInstance().newXPath()
                .evaluate("/svg/g/path", document, XPathConstants.NODESET) as org.w3c.dom.NodeList
            assertTrue(visiblePaths.length > 0)
            val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                translate(100f, 100f)
                for (index in 0 until visiblePaths.length) {
                    val element = visiblePaths.item(index) as org.w3c.dom.Element
                    assertEquals("evenodd", element.getAttribute("fill-rule"))
                    val path = PathParser.createPathFromPathData(element.getAttribute("d"))!!
                    path.fillType = Path.FillType.EVEN_ODD
                    val paint = Paint()
                    paint.color = Color.parseColor(element.getAttribute("fill"))
                    drawPath(path, paint)
                }
            }
            assertEquals(Color.RED, bitmap.getPixel((seed + 100f).toInt(), (seed + 100f).toInt()))
            assertEquals(Color.WHITE, bitmap.getPixel(150, 150))
            assertEquals(if (inverse) Color.WHITE else Color.RED, bitmap.getPixel(100, 100))
            if (inverse) {
                val operations = canvas.getPathsMap().map { (path, options) -> Path(path) to options }
                val recolored = BucketFill(Color.WHITE, operations).fill(-10000f, 10000f, Color.BLUE)!!
                assertTrue(recolored.isInverseFill)
            }
            bitmap.recycle()
            file.delete()
        }
    }

    @Test
    fun legacySvgStrokesAndErasersStillLoad() {
        val xml = """<svg width="100" height="100" xmlns="http://www.w3.org/2000/svg">
            <rect width="100" height="100" fill="#ffffff"/>
            <path d="M10,10 Q20,20 30,30 L40,40" fill="none" stroke="#000000" stroke-width="4"/>
            <path d="M20,20 L25,25" fill="none" stroke="none" stroke-width="8"/>
            </svg>"""
        val drawing = Svg.parseSvg(xml.byteInputStream())
        assertEquals(2, drawing.paths.size)
        assertFalse(drawing.paths.values.first().isFill)
        assertTrue(drawing.paths.values.last().isEraser)
        assertEquals(RectF(0f, 0f, 100f, 100f), drawing.viewport)
    }

    @Test
    fun compositedFillPreservesImportedPixelsAndUndoRedo() {
        val canvas = canvas()
        val image = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        for (y in 0 until 100) for (x in 0 until 100) {
            image.setPixel(x, y, if (x < 50) Color.WHITE else Color.rgb(x, y, 100))
        }
        canvas.mBackgroundBitmap = image
        val divider = MyPath().apply { moveTo(50f, 0f); lineTo(50f, 100f) }
        canvas.addOperation(divider, PaintOptions(strokeWidth = 4f))
        canvas.toggleBucketFill(true)
        canvas.setColor(Color.RED)
        tap(canvas, 20f, 50f)
        shadowOf(Looper.getMainLooper()).idle()
        val filled = canvas.settledBitmap()
        assertEquals(Color.RED, filled.getPixel(20, 50))
        assertEquals(Color.BLACK, filled.getPixel(50, 50))
        for (y in 0 until 100) for (x in 55 until 100) {
            assertEquals(image.getPixel(x, y), filled.getPixel(x, y))
        }
        canvas.undo()
        assertEquals(Color.WHITE, canvas.settledBitmap().getPixel(20, 50))
        canvas.redo()
        assertBitmapEquals(filled, canvas.settledBitmap())
        canvas.clearCanvas()
        assertEquals(Color.WHITE, canvas.settledBitmap().getPixel(20, 50))
        canvas.undo()
        assertBitmapEquals(filled, canvas.settledBitmap())
    }

    private fun activityCanvas(activity: MainActivity) =
        activity.findViewById<MyCanvas>(org.fossify.paint.R.id.my_canvas).apply {
            drawingExecutor = Executor { it.run() }
        }

    private fun MyCanvas.settledBitmap(): Bitmap {
        shadowOf(Looper.getMainLooper()).idle()
        return getBitmap()
    }

    private fun canvas(): MyCanvas {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val canvas = MyCanvas(activity, Robolectric.buildAttributeSet().build())
        canvas.drawingExecutor = Executor { it.run() }
        canvas.updateBackgroundColor(Color.WHITE)
        activity.setContentView(canvas, ViewGroup.LayoutParams(100, 100))
        shadowOf(Looper.getMainLooper()).idle()
        canvas.layout(0, 0, 100, 100)
        return canvas
    }

    private fun deliverResult(activity: MainActivity, requestCode: Int, data: Intent) {
        MainActivity::class.java.getDeclaredMethod(
            "onActivityResult", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java
        ).apply { isAccessible = true }.invoke(activity, requestCode, Activity.RESULT_OK, data)
    }

    private fun box(left: Float, top: Float, right: Float, bottom: Float) = MyPath().apply {
        moveTo(left, top)
        lineTo(right, top)
        lineTo(right, bottom)
        lineTo(left, bottom)
        close()
    }

    private fun tap(canvas: MyCanvas, x: Float, y: Float) {
        event(canvas, MotionEvent.ACTION_DOWN, x, y)
        event(canvas, MotionEvent.ACTION_UP, x, y)
    }

    private fun event(canvas: MyCanvas, action: Int, x: Float, y: Float) {
        MotionEvent.obtain(0, 10, action, x, y, 0).also {
            canvas.onTouchEvent(it)
            it.recycle()
        }
    }

    private fun pinch(canvas: MyCanvas) {
        val start = SystemClock.uptimeMillis()
        val centerX = canvas.width / 2f
        val centerY = canvas.height / 2f
        val points = arrayOf(
            MotionEvent.PointerCoords().apply { x = centerX - 150f; y = centerY; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = centerX + 150f; y = centerY; pressure = 1f; size = 1f }
        )
        val properties = Array(2) { index ->
            MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER }
        }
        fun send(action: Int, count: Int, time: Long) {
            MotionEvent.obtain(start, time, action, count, properties, points, 0, 0, 1f, 1f, 0, 0, 0, 0).also {
                canvas.onTouchEvent(it)
                it.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, 1, start)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, start + 10)
        var span = 300f
        for (step in 1..8) {
            span *= 1.05f
            points[0].x = centerX - span / 2
            points[1].x = centerX + span / 2
            send(MotionEvent.ACTION_MOVE, 2, start + 10 + step * 20)
        }
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, start + 200)
        send(MotionEvent.ACTION_UP, 1, start + 210)
    }

    private fun addStroke(canvas: MyCanvas, color: Int, y: Float) {
        val path = MyPath().apply { moveTo(10f, y); lineTo(80f, y) }
        canvas.addOperation(path, PaintOptions(color = color, strokeWidth = 5f))
    }

    private fun finishFills(pendingFills: ArrayDeque<Runnable>) {
        shadowOf(Looper.getMainLooper()).idle()
        while (pendingFills.isNotEmpty()) {
            pendingFills.removeFirst().run()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun assertBitmapEquals(expected: Bitmap, actual: Bitmap) {
        val first = IntArray(expected.width * expected.height)
        val second = IntArray(actual.width * actual.height)
        expected.getPixels(first, 0, expected.width, 0, 0, expected.width, expected.height)
        actual.getPixels(second, 0, actual.width, 0, 0, actual.width, actual.height)
        assertArrayEquals(first, second)
    }
}
