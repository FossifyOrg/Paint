package org.fossify.paint.views

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.PointF
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.MotionEvent.INVALID_POINTER_ID
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withMatrix
import androidx.core.view.doOnLayout
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.request.RequestOptions
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.paint.R
import org.fossify.paint.extensions.removeFirst
import org.fossify.paint.extensions.removeLast
import org.fossify.paint.extensions.removeLastOrNull
import org.fossify.paint.helpers.BucketFill
import org.fossify.paint.helpers.DrawingRenderer
import org.fossify.paint.interfaces.CanvasListener
import org.fossify.paint.models.MyPath
import org.fossify.paint.models.PaintOptions
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlin.math.abs

class MyCanvas(context: Context, attrs: AttributeSet) : View(context, attrs) {
    private val MAX_HISTORY_COUNT = 1000

    private val mScaledTouchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var mOperations = LinkedHashMap<MyPath, PaintOptions>()
    var mBackgroundBitmap: Bitmap? = null
    var mListener: CanvasListener? = null

    private var mUndoneOperations = LinkedHashMap<MyPath, PaintOptions>()
    private var mLastOperations = LinkedHashMap<MyPath, PaintOptions>()
    private var mLastBackgroundBitmap: Bitmap? = null

    private var mPaint = Paint()
    private val mRenderer = DrawingRenderer()
    private var mPath = MyPath()
    private var mPaintOptions = PaintOptions()

    private var mCurX = 0f
    private var mCurY = 0f
    private var mStartX = 0f
    private var mStartY = 0f
    private var mPosX = 0f
    private var mPosY = 0f
    private var mLastTouchX = 0f
    private var mLastTouchY = 0f
    private var mActivePointerId = INVALID_POINTER_ID

    private var mCurrBrushSize = 0f
    private var mAllowMovingZooming = true
    private var mIsEraserOn = false
    private var mRelativeBrushSize = true
    private var mIsBucketFillOn = false
    private var mWasMultitouch = false
    private var mIgnoreTouches = false
    private var mIgnoreMultitouchChanges = false
    private var mWasScalingInGesture = false
    private var mWasMovingCanvasInGesture = false
    private var mBackgroundColor = Color.WHITE
    private var mDrawingInProgress = false
    private val mDrawingHandler = Handler(Looper.getMainLooper())
    private val mPendingEdits = ArrayDeque<DrawingEdit>()
    internal var drawingExecutor = Executor { task -> ensureBackgroundThread { task.run() } }

    private var mScaleDetector: ScaleGestureDetector? = null
    private var mScaleFactor = 1f

    private var mLastMotionEvent: MotionEvent? = null
    private var mTouchSloppedBeforeMultitouch: Boolean = false

    init {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            // Android 8's hardware renderer clips inverse fills to the path bounds.
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }
        mPaint.apply {
            color = mPaintOptions.color
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            strokeWidth = mPaintOptions.strokeWidth
            isAntiAlias = true
        }

        mScaleDetector = ScaleGestureDetector(context, ScaleListener()).apply {
            isQuickScaleEnabled = false
        }

        updateUndoVisibility()
    }

    public override fun onSaveInstanceState(): Parcelable? {
        DrawingStateHolder.operations = mOperations
        return super.onSaveInstanceState()
    }

    public override fun onRestoreInstanceState(state: Parcelable) {
        val savedOperations = DrawingStateHolder.operations
        if (savedOperations != null) {
            whenDrawingReady { mOperations = savedOperations }
        }
        super.onRestoreInstanceState(state)
        updateUndoVisibility()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        whenDrawingReady {}
    }

    override fun onDetachedFromWindow() {
        mLastMotionEvent?.recycle()
        mLastMotionEvent = null
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (mAllowMovingZooming) {
            mScaleDetector!!.onTouchEvent(event)
        }

        val action = event.actionMasked
        if (action == MotionEvent.ACTION_CANCEL) {
            mActivePointerId = INVALID_POINTER_ID
            mIgnoreTouches = false
            mIgnoreMultitouchChanges = false
            mWasMultitouch = false
            mWasScalingInGesture = false
            mWasMovingCanvasInGesture = false
            mTouchSloppedBeforeMultitouch = false
            mPath.reset()
            invalidate()
            return true
        }
        if (mIgnoreTouches && action == MotionEvent.ACTION_UP) {
            mIgnoreTouches = false
            mWasScalingInGesture = false
            mWasMovingCanvasInGesture = false
            return true
        }

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            mActivePointerId = event.getPointerId(0)
        }

        val pointerIndex = event.findPointerIndex(mActivePointerId)
        val x: Float
        val y: Float

        try {
            x = event.getX(pointerIndex)
            y = event.getY(pointerIndex)
        } catch (e: Exception) {
            return true
        }

        val point = floatArrayOf(x, y)
        val inverse = Matrix()
        canvasMatrix().invert(inverse)
        inverse.mapPoints(point)
        val newValueX = point[0]
        val newValueY = point[1]

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                mWasScalingInGesture = false
                mWasMovingCanvasInGesture = false
                mWasMultitouch = false
                mStartX = x
                mStartY = y
                mLastTouchX = x
                mLastTouchY = y
                actionDown(newValueX, newValueY)
            }

            MotionEvent.ACTION_MOVE -> {
                if (mTouchSloppedBeforeMultitouch) {
                    mPath.reset()
                    mTouchSloppedBeforeMultitouch = false
                }

                if (!mIsBucketFillOn && (!mAllowMovingZooming || (!mScaleDetector!!.isInProgress && event.pointerCount == 1 && !mWasMultitouch))) {
                    actionMove(newValueX, newValueY)
                }

                if (mAllowMovingZooming && mWasMultitouch && !mIgnoreMultitouchChanges) {
                    mPosX += x - mLastTouchX
                    mPosY += y - mLastTouchY
                    mWasMovingCanvasInGesture = true
                    invalidate()
                }

                mLastTouchX = x
                mLastTouchY = y
                mIgnoreMultitouchChanges = false
            }

            MotionEvent.ACTION_UP -> {
                mActivePointerId = INVALID_POINTER_ID
                val isInsideCanvas = x >= 0 && x < width && y >= 0 && y < height
                val fillPoint = if (!mWasMultitouch && isInsideCanvas) {
                    PointF(newValueX, newValueY)
                } else {
                    null
                }
                actionUp(false, fillPoint)
                mWasScalingInGesture = false
                mWasMovingCanvasInGesture = false
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (mAllowMovingZooming) {
                    mWasMultitouch = true
                    mIgnoreMultitouchChanges = true
                    mTouchSloppedBeforeMultitouch =
                        mLastMotionEvent.isTouchSlop(pointerIndex, mStartX, mStartY)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (mAllowMovingZooming) {
                    mIgnoreTouches = true
                    mIgnoreMultitouchChanges = true
                    actionUp(!mWasScalingInGesture && !mWasMovingCanvasInGesture)
                }
            }
        }

        mLastMotionEvent?.recycle()
        mLastMotionEvent = MotionEvent.obtain(event)
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawDrawing(canvas, true)
    }

    private fun drawDrawing(canvas: Canvas, includePendingEdits: Boolean) {
        canvas.withMatrix(canvasMatrix()) {
            mRenderer.draw(this)

            if (includePendingEdits) {
                for (edit in mPendingEdits) {
                    if (edit is DrawingEdit.Stroke) {
                        changePaint(edit.options)
                        drawPath(edit.path, mPaint)
                    }
                }
                changePaint(mPaintOptions)
                drawPath(mPath, mPaint)
            }
        }
    }

    fun undo() {
        whenDrawingReady { applyUndo() }
    }

    private fun applyUndo() {
        if (mOperations.isEmpty() && mLastOperations.isNotEmpty()) {
            mOperations = mLastOperations.clone() as LinkedHashMap<MyPath, PaintOptions>
            mBackgroundBitmap = mLastBackgroundBitmap
            mLastOperations.clear()
            return
        }

        if (mOperations.isNotEmpty()) {
            val (path, paintOptions) = mOperations.removeLastOrNull()
            if (paintOptions != null && path != null) {
                mUndoneOperations[path] = paintOptions
            }
        }
    }

    fun redo() {
        whenDrawingReady { applyRedo() }
    }

    private fun applyRedo() {
        if (mUndoneOperations.isNotEmpty()) {
            val (path, paintOptions) = mUndoneOperations.removeLast()
            recordOperation(path, paintOptions, clearRedo = false)
        }
    }

    fun toggleEraser(isEraserOn: Boolean) {
        mIsEraserOn = isEraserOn
        mPaintOptions.isEraser = isEraserOn
        invalidate()
    }

    fun toggleBucketFill(isBucketFillOn: Boolean) {
        mIsBucketFillOn = isBucketFillOn
    }

    fun setColor(newColor: Int) {
        mPaintOptions.color = newColor
    }

    fun updateBackgroundColor(newColor: Int) {
        whenDrawingReady { applyBackgroundColor(newColor) }
    }

    private fun applyBackgroundColor(newColor: Int) {
        mBackgroundColor = newColor
        setBackgroundColor(newColor)
        mBackgroundBitmap = null
    }

    fun setBrushSize(newBrushSize: Float) {
        mCurrBrushSize = newBrushSize
        mPaintOptions.strokeWidth = resources.getDimension(R.dimen.full_brush_size) * (mCurrBrushSize / 100f)
        if (mRelativeBrushSize) {
            mPaintOptions.strokeWidth /= mScaleFactor
        }
    }

    fun setAllowZooming(allowZooming: Boolean) {
        mAllowMovingZooming = allowZooming
    }

    fun setRelativeBrushSize(relativeBrushSize: Boolean) {
        mRelativeBrushSize = relativeBrushSize
        setBrushSize(mCurrBrushSize)
    }

    fun getBitmap(includePendingEdits: Boolean = true): Bitmap {
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        if (includePendingEdits) {
            draw(canvas)
        } else {
            canvas.drawColor(mBackgroundColor)
            drawDrawing(canvas, false)
        }
        return bitmap
    }

    fun drawBitmap(activity: Activity, path: Any) {
        ensureBackgroundThread {
            val size = Point()
            activity.windowManager.defaultDisplay.getSize(size)
            val options =
                RequestOptions().format(DecodeFormat.PREFER_ARGB_8888).disallowHardwareConfig()
                    .fitCenter()

            try {
                val builder =
                    Glide.with(context).asBitmap().load(path).apply(options).submit(size.x, size.y)

                val bitmap = builder.get()
                activity.runOnUiThread {
                    whenDrawingReady { mBackgroundBitmap = bitmap }
                }
            } catch (e: ExecutionException) {
                val errorMsg =
                    String.format(activity.getString(R.string.failed_to_load_image), path)
                activity.toast(errorMsg)
            }
        }
    }

    private fun changePaint(paintOptions: PaintOptions) {
        paintOptions.applyTo(mPaint, mBackgroundColor)
    }

    fun clearCanvas() {
        mPath.reset()
        whenDrawingReady { applyClear() }
    }

    private fun applyClear() {
        mLastOperations = mOperations.clone() as LinkedHashMap<MyPath, PaintOptions>
        mLastBackgroundBitmap = mBackgroundBitmap
        mBackgroundBitmap = null
        mOperations.clear()
        mUndoneOperations.clear()
    }

    internal fun replaceDrawing(viewport: RectF, backgroundColor: Int, paths: Map<MyPath, PaintOptions>) {
        applyViewportBounds(viewport)
        applyClear()
        applyBackgroundColor(backgroundColor)
        for ((path, options) in paths) recordOperation(path, options)
    }

    private fun actionDown(x: Float, y: Float) {
        mPath.reset()
        mPath.moveTo(x, y)
        mCurX = x
        mCurY = y
    }

    private fun actionMove(x: Float, y: Float) {
        mPath.quadTo(mCurX, mCurY, (x + mCurX) / 2, (y + mCurY) / 2)
        mCurX = x
        mCurY = y
    }

    private fun actionUp(forceLineDraw: Boolean, fillPoint: PointF? = null) {
        if (mIsBucketFillOn) {
            fillPoint?.let { bucketFill(it.x, it.y) }
        } else if (!mWasMultitouch || forceLineDraw) {
            drawADot()
        }

        mPath = MyPath()
        mPaintOptions =
            PaintOptions(mPaintOptions.color, mPaintOptions.strokeWidth, mPaintOptions.isEraser)
    }

    private fun updateUndoRedoVisibility() {
        updateUndoVisibility()
        updateRedoVisibility()
        updateClearConfirmation()
    }

    private fun updateUndoVisibility() {
        val hasEdits = mOperations.isNotEmpty() || mLastOperations.isNotEmpty()
        mListener?.toggleUndoVisibility(hasEdits || hasPendingEdits())
    }

    private fun updateRedoVisibility() {
        mListener?.toggleRedoVisibility(mUndoneOperations.isNotEmpty() && !hasPendingEdits())
    }

    private fun updateClearConfirmation() {
        val hasContent = mBackgroundBitmap != null || mOperations.isNotEmpty() || hasPendingEdits()
        mListener?.toggleHasContent(hasContent)
    }

    private fun bucketFill(x: Float, y: Float) {
        submitEdit(DrawingEdit.Fill(x, y, mPaintOptions.color))
    }

    fun whenDrawingReady(action: () -> Unit) {
        submitEdit(DrawingEdit.Action(action))
    }

    private fun submitEdit(edit: DrawingEdit) {
        mPendingEdits.addLast(edit)
        processNextEdit()
        updateUndoRedoVisibility()
        invalidate()
    }

    private fun processNextEdit() {
        if (mDrawingInProgress) return
        while (!prepareDrawing()) {
            when (val edit = mPendingEdits.removeFirstOrNull() ?: return) {
                is DrawingEdit.Fill -> {
                    fillRegion(edit)
                    return
                }
                is DrawingEdit.Stroke -> recordOperation(edit.path, edit.options)
                is DrawingEdit.Action -> edit.action()
            }
        }
    }

    private fun drawingSnapshot(): DrawingRenderer.Snapshot {
        val bitmap = mBackgroundBitmap
        val origin = bitmap?.let { PointF((width - it.width) / 2f, (height - it.height) / 2f) } ?: PointF()
        return DrawingRenderer.Snapshot(mOperations.toList(), mBackgroundColor, bitmap, origin)
    }

    private fun prepareDrawing(): Boolean {
        val snapshot = drawingSnapshot()
        if (mRenderer.update(snapshot)) return false
        val previous = mRenderer.drawing
        mDrawingInProgress = true
        drawingExecutor.execute {
            val result = runCatching { DrawingRenderer.prepare(snapshot, previous) }
            mDrawingHandler.post {
                mRenderer.drawing = result.getOrElse { error ->
                    reportDrawingError(error)
                    DrawingRenderer.Drawing(snapshot)
                }
                finishDrawingWork()
            }
        }
        return true
    }

    private fun fillRegion(request: DrawingEdit.Fill) {
        val snapshot = drawingSnapshot()
        val previous = mRenderer.drawing
        val filler = BucketFill(
            snapshot.backgroundColor,
            snapshot.operations.map { (path, options) -> Path(path) to options.copy() },
            snapshot.bitmap,
            snapshot.origin
        )
        mDrawingInProgress = true
        drawingExecutor.execute {
            val result = runCatching {
                val path = filler.fill(request.x, request.y, request.color) ?: return@runCatching null
                val options = PaintOptions(color = request.color, isFill = true)
                val operations = (snapshot.operations + (path to options)).takeLast(MAX_HISTORY_COUNT)
                path to DrawingRenderer.prepare(snapshot.copy(operations = operations), previous)
            }
            mDrawingHandler.post {
                result.fold(
                    onSuccess = { completed ->
                        completed?.let { (path, drawing) ->
                            recordOperation(path, drawing.snapshot.operations.last().second)
                            mRenderer.drawing = drawing
                        }
                    },
                    onFailure = ::reportDrawingError
                )
                finishDrawingWork()
            }
        }
    }

    private fun finishDrawingWork() {
        mDrawingInProgress = false
        processNextEdit()
        updateUndoRedoVisibility()
        invalidate()
    }

    private fun reportDrawingError(error: Throwable) {
        Log.e("Paint", "Failed to prepare drawing", error)
        context.toast(R.string.unknown_error_occurred)
    }

    private sealed interface DrawingEdit {
        data class Fill(val x: Float, val y: Float, val color: Int) : DrawingEdit
        data class Stroke(val path: MyPath, val options: PaintOptions) : DrawingEdit
        data class Action(val action: () -> Unit) : DrawingEdit
    }

    private fun drawADot() {
        mPath.lineTo(mCurX, mCurY)

        addOperation(mPath, mPaintOptions)
    }

    fun addOperation(path: MyPath, paintOptions: PaintOptions) {
        submitEdit(DrawingEdit.Stroke(path, paintOptions.copy()))
    }

    private fun recordOperation(path: MyPath, paintOptions: PaintOptions, clearRedo: Boolean = true) {
        mOperations[path] = paintOptions
        if (clearRedo) mUndoneOperations.clear()

        while (mOperations.size > MAX_HISTORY_COUNT) {
            mOperations.removeFirst()
        }
    }

    private fun canvasMatrix() = Matrix().apply {
        preTranslate(mPosX, mPosY)
        preScale(mScaleFactor, mScaleFactor, width / 2f, height / 2f)
    }

    fun getViewportBounds(): RectF {
        val inverse = Matrix()
        canvasMatrix().invert(inverse)
        return RectF(0f, 0f, width.toFloat(), height.toFloat()).apply { inverse.mapRect(this) }
    }

    fun setViewportBounds(bounds: RectF) {
        doOnLayout { applyViewportBounds(bounds) }
    }

    internal fun applyViewportBounds(bounds: RectF) {
        require(bounds.width() > 0f && bounds.height() > 0f)
        val scale = minOf(width / bounds.width(), height / bounds.height())
        val posX = (width / 2f - bounds.centerX()) * scale
        val posY = (height / 2f - bounds.centerY()) * scale
        require(scale > 0f && listOf(scale, posX, posY).all { it.isFinite() })
        mScaleFactor = scale
        mPosX = posX
        mPosY = posY
        setBrushSize(mCurrBrushSize)
        invalidate()
    }

    fun getPathsMap() = mOperations

    internal fun getPreparedDrawing() = mRenderer.drawing

    fun hasPendingEdits() = mDrawingInProgress || mPendingEdits.isNotEmpty()

    fun getDrawingHashCode(): Long = if (mOperations.isEmpty()) {
        0
    } else {
        mOperations.hashCode().toLong() + (mBackgroundBitmap?.hashCode()?.toLong() ?: 0L)
    }

    private fun MotionEvent?.isTouchSlop(pointerIndex: Int, startX: Float, startY: Float): Boolean {
        return !(this == null || actionMasked != MotionEvent.ACTION_MOVE) && try {
            val moveX = abs(getX(pointerIndex) - startX)
            val moveY = abs(getY(pointerIndex) - startY)

            moveX <= mScaledTouchSlop && moveY <= mScaledTouchSlop
        } catch (_: Exception) {
            false
        }
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (!mWasScalingInGesture) {
                mPath.reset()
            }

            val newScale = (mScaleFactor * detector.scaleFactor).coerceIn(
                minOf(0.1f, mScaleFactor), maxOf(10.0f, mScaleFactor)
            )
            if (newScale == mScaleFactor) return true
            val factor = newScale / mScaleFactor
            mIgnoreTouches = true
            mWasScalingInGesture = true
            mScaleFactor = newScale

            mPosX *= factor
            mPosY *= factor

            setBrushSize(mCurrBrushSize)
            invalidate()
            return true
        }
    }
}

object DrawingStateHolder {
    var operations: LinkedHashMap<MyPath, PaintOptions>? = null
}
