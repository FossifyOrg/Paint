package org.fossify.paint

import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.paint.activities.MainActivity
import org.fossify.paint.models.MyPath
import org.fossify.paint.models.PaintOptions
import org.fossify.paint.views.MyCanvas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CanvasFillDeviceTest {
    @get:Rule
    val scenario = ActivityScenarioRule(MainActivity::class.java)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before
    fun resetDrawing() {
        instrumentation.waitForIdleSync()
        scenario.scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        withCanvas {
            it.clearCanvas()
            it.updateBackgroundColor(Color.WHITE)
            it.setViewportBounds(RectF(0f, 0f, it.width.toFloat(), it.height.toFloat()))
            it.toggleBucketFill(true)
            it.setColor(Color.RED)
        }
    }

    @Test
    fun blankInfiniteFillIsVisibleOnHardwareCanvas() {
        withCanvas { canvas -> tap(canvas, canvas.width / 2f, canvas.height / 2f) }
        awaitOperations(1)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        withCanvas { canvas ->
            val location = IntArray(2)
            canvas.getLocationOnScreen(location)
            assertEquals(
                Color.RED,
                screenshot.getPixel(location[0] + canvas.width / 2, location[1] + canvas.height / 2)
            )
        }
        screenshot.recycle()
    }

    @Test
    fun outsideFillAndItsHoleRemainVisibleAfterPanningAndZooming() {
        var width = 0f
        var height = 0f
        withCanvas { canvas ->
            assertTrue(canvas.isHardwareAccelerated)
            width = canvas.width.toFloat()
            height = canvas.height.toFloat()
            canvas.addOperation(
                box(width * 0.3f, height * 0.3f, width * 0.7f, height * 0.7f),
                PaintOptions(strokeWidth = 10f)
            )
            tap(canvas, width * 0.1f, height * 0.5f)
        }
        awaitOperations(2)
        for (viewport in listOf(
            RectF(0f, 0f, width, height),
            RectF(-width, -height, width * 2, height * 2),
            RectF(100000f, 100000f, 100000f + width, 100000f + height)
        )) {
            withCanvas { it.setViewportBounds(viewport) }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(250)
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            withCanvas { canvas ->
                val location = IntArray(2)
                canvas.getLocationOnScreen(location)
                val expected = canvas.getBitmap()
                for (fraction in listOf(0.1f, 0.5f, 0.9f)) {
                    val x = (width * fraction).toInt()
                    val y = (height * 0.5f).toInt()
                    assertEquals(expected.getPixel(x, y), screenshot.getPixel(location[0] + x, location[1] + y))
                }
                expected.recycle()
            }
            screenshot.recycle()
        }
    }

    @Test
    fun toolbarBucketFillsEnclosedRegionThroughRealTouchDispatch() {
        val location = IntArray(2)
        lateinit var center: PointF
        lateinit var outside: PointF
        withCanvas { canvas ->
            canvas.toggleBucketFill(false)
            canvas.getLocationOnScreen(location)
            center = PointF(canvas.width * 0.5f, canvas.height * 0.45f)
            outside = PointF(canvas.width * 0.1f, center.y)
            val left = canvas.width * 0.3f
            val right = canvas.width * 0.7f
            val top = canvas.height * 0.28f
            val bottom = canvas.height * 0.62f
            canvas.addOperation(box(left, top, right, bottom), PaintOptions(strokeWidth = 10f))
        }
        val start = SystemClock.uptimeMillis()
        fun send(action: Int, point: PointF) {
            MotionEvent.obtain(
                start, SystemClock.uptimeMillis(), action, location[0] + point.x, location[1] + point.y, 0
            ).also {
                instrumentation.sendPointerSync(it)
                it.recycle()
            }
        }
        scenario.scenario.onActivity { activity ->
            activity.findViewById<MyCanvas>(R.id.my_canvas).setColor(Color.RED)
            activity.findViewById<View>(R.id.bucket_fill).performClick()
        }
        send(MotionEvent.ACTION_DOWN, center)
        send(MotionEvent.ACTION_UP, center)
        awaitOperations(2)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        assertEquals(Color.RED, screenshot.getPixel(location[0] + center.x.toInt(), location[1] + center.y.toInt()))
        assertEquals(Color.WHITE, screenshot.getPixel(location[0] + outside.x.toInt(), location[1] + outside.y.toInt()))
        screenshot.recycle()
    }

    private fun withCanvas(block: (MyCanvas) -> Unit) {
        scenario.scenario.onActivity { block(it.findViewById(R.id.my_canvas)) }
    }

    private fun awaitOperations(count: Int) {
        var actual = 0
        var pending = true
        val deadline = SystemClock.uptimeMillis() + 10000
        do {
            instrumentation.waitForIdleSync()
            withCanvas {
                actual = it.getPathsMap().size
                pending = it.hasPendingEdits()
            }
            if (actual == count && !pending) return
            SystemClock.sleep(10)
        } while (SystemClock.uptimeMillis() < deadline)
        assertEquals(count, actual)
        assertTrue(!pending)
    }

    private fun box(left: Float, top: Float, right: Float, bottom: Float) = MyPath().apply {
        moveTo(left, top)
        lineTo(right, top)
        lineTo(right, bottom)
        lineTo(left, bottom)
        close()
    }

    private fun tap(canvas: MyCanvas, x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(time, time + action, action, x, y, 0).also {
                canvas.onTouchEvent(it)
                it.recycle()
            }
        }
    }
}
