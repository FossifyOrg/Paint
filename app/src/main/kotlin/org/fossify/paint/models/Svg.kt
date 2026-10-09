package org.fossify.paint.models

import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.sax.RootElement
import android.util.Log
import androidx.core.view.doOnLayout
import org.fossify.commons.extensions.getFileOutputStream
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.models.FileDirItem
import org.fossify.paint.R
import org.fossify.paint.activities.MainActivity
import org.fossify.paint.activities.SimpleActivity
import org.fossify.paint.helpers.DrawingRenderer
import org.fossify.paint.views.MyCanvas
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import javax.xml.parsers.SAXParserFactory

private const val RGB_MASK = 0xffffff

object Svg {
    fun saveSvg(activity: SimpleActivity, path: String, canvas: MyCanvas, onSaved: (Long) -> Unit = {}) {
        activity.getFileOutputStream(FileDirItem(path, path.getFilenameFromPath()), true) {
            saveToOutputStream(activity, it, canvas, onSaved)
        }
    }

    fun saveToOutputStream(
        activity: SimpleActivity,
        outputStream: OutputStream?,
        canvas: MyCanvas,
        onSaved: (Long) -> Unit = {}
    ) {
        if (outputStream != null) {
            canvas.whenDrawingReady {
                try {
                    BufferedWriter(OutputStreamWriter(outputStream)).use { writer ->
                        writeSvg(
                            writer, canvas.getPreparedDrawing(), canvas.width, canvas.height, canvas.getViewportBounds()
                        )
                    }
                    onSaved(canvas.getDrawingHashCode())
                    activity.toast(R.string.file_saved)
                } catch (e: IOException) {
                    activity.showErrorToast(e)
                }
            }
        } else {
            activity.toast(R.string.unknown_error_occurred)
        }
    }

    internal fun writeSvg(
        writer: Writer,
        drawing: DrawingRenderer.Drawing,
        width: Int,
        height: Int,
        viewport: RectF = RectF(0f, 0f, width.toFloat(), height.toFloat())
    ) {
        writer.apply {
            write("<svg width=\"$width\" height=\"$height\" ")
            write("viewBox=\"${viewport.left} ${viewport.top} ${viewport.width()} ${viewport.height()}\" ")
            write("xmlns=\"http://www.w3.org/2000/svg\">")
            write("<rect x=\"${viewport.left}\" y=\"${viewport.top}\" ")
            write("width=\"${viewport.width()}\" height=\"${viewport.height()}\" ")
            write("fill=\"${colorString(drawing.snapshot.backgroundColor)}\"/>")
            writeDrawing(this, drawing, viewport)
            write("</svg>")
        }
    }

    private fun writeDrawing(
        writer: Writer,
        drawing: DrawingRenderer.Drawing,
        viewport: RectF
    ) {
        val operations = drawing.snapshot.operations
        val regions = drawing.regions
        if (regions == null) {
            for ((path, options) in operations) writePath(writer, path, options, viewport)
            return
        }
        writer.apply {
            write("<defs><g id=\"paint-history\">")
            for ((path, options) in operations) writePath(this, path, options, viewport)
            write("</g>")
            write("<filter id=\"paint-coverage\" filterUnits=\"userSpaceOnUse\" ")
            write("x=\"${viewport.left}\" y=\"${viewport.top}\" ")
            write("width=\"${viewport.width()}\" height=\"${viewport.height()}\" ")
            write("color-interpolation-filters=\"sRGB\">")
            write("<feComponentTransfer><feFuncA type=\"linear\" ")
            write("slope=\"0\" intercept=\"1\"/></feComponentTransfer></filter></defs>")
            write("<g filter=\"url(#paint-coverage)\">")
            for ((color, path) in regions.paths) {
                val options = PaintOptions(color = color ?: drawing.snapshot.backgroundColor, isFill = true)
                writePath(this, path, options, viewport)
            }
            write("</g><g>")
            for ((path, options) in operations.drop(drawing.snapshot.fillEnd)) writePath(this, path, options, viewport)
            write("</g>")
        }
    }

    private fun writePath(
        writer: Writer,
        path: MyPath,
        options: PaintOptions,
        viewport: RectF
    ) {
        writer.apply {
            write("<path d=\"")
            if (options.isFill && path.isInverseFill) {
                write("M${viewport.left},${viewport.top} L${viewport.right},${viewport.top} ")
                write("L${viewport.right},${viewport.bottom} L${viewport.left},${viewport.bottom} Z ")
            }
            path.actions.forEach {
                it.perform(this)
                write(" ")
            }
            val color = if (options.isEraser) "none" else colorString(options.color)
            if (options.isFill) {
                write("\" fill=\"$color\" fill-rule=\"evenodd\" stroke=\"none\"")
                if (path.isInverseFill) write(" data-inverse-fill=\"true\"")
            } else {
                write("\" fill=\"none\" stroke=\"$color\" stroke-width=\"${options.strokeWidth}\"")
                write(" stroke-linecap=\"round\" stroke-linejoin=\"round\"")
            }
            write("/>")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun loadSvg(activity: MainActivity, fileOrUri: Any, canvas: MyCanvas) {
        try {
            val stream = when (fileOrUri) {
                is File -> FileInputStream(fileOrUri)
                is Uri -> activity.contentResolver.openInputStream(fileOrUri)
                else -> null
            } ?: return
            val svg = stream.use { parseSvg(it) }
            canvas.doOnLayout {
                canvas.whenDrawingReady {
                    runCatching {
                        canvas.replaceDrawing(svg.viewport, svg.backgroundColor, svg.paths)
                        activity.updateBackgroundControls(svg.backgroundColor)
                    }.onFailure { showLoadError(activity, it) }
                }
            }
        } catch (e: Exception) {
            showLoadError(activity, e)
        }
    }

    private fun showLoadError(activity: MainActivity, error: Throwable) {
        Log.e("Paint", "Failed to load SVG", error)
        activity.toast(R.string.unknown_error_occurred)
    }

    @Suppress("DestructuringDeclarationWithTooManyEntries")
    internal fun parseSvg(inputStream: InputStream): Drawing {
        val svg = Drawing()
        val namespace = "http://www.w3.org/2000/svg"
        val root = RootElement(namespace, "svg")
        root.setStartElementListener { attributes ->
            val viewBox = attributes.getValue("viewBox")
            svg.viewport = if (viewBox == null) {
                RectF(0f, 0f, attributes.getValue("width").toFloat(), attributes.getValue("height").toFloat())
            } else {
                val (left, top, width, height) = viewBox.trim().split("[\\s,]+".toRegex()).map(String::toFloat)
                RectF(left, top, left + width, top + height)
            }
        }
        root.getChild(namespace, "rect").setStartElementListener { attributes ->
            svg.backgroundColor = Color.parseColor(attributes.getValue("fill"))
        }
        root.getChild(namespace, "path").setStartElementListener { readPath(it, svg) }
        var isHistory = false
        val history = root.getChild(namespace, "defs").getChild(namespace, "g")
        history.setStartElementListener { isHistory = it.getValue("id") == "paint-history" }
        history.getChild(namespace, "path").setStartElementListener { if (isHistory) readPath(it, svg) }
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        factory.newSAXParser().xmlReader.apply {
            contentHandler = root.contentHandler
        }.parse(InputSource(inputStream))
        validateViewport(svg.viewport)
        return svg
    }

    private fun validateViewport(viewport: RectF) {
        require(viewport.run {
            listOf(left, top, right, bottom, width(), height()).all { it.isFinite() } && width() > 0f && height() > 0f
        })
    }

    private fun colorString(color: Int) = "#%06x".format(color and RGB_MASK)

    internal class Drawing {
        var backgroundColor = Color.WHITE
        var viewport = RectF()
        val paths = LinkedHashMap<MyPath, PaintOptions>()
    }
}

private fun readPath(attributes: Attributes, drawing: Svg.Drawing) {
    val fill = attributes.getValue("fill") ?: "none"
    val stroke = attributes.getValue("stroke") ?: "none"
    val isFill = fill != "none"
    val isEraser = !isFill && stroke == "none"
    val color = if (isEraser) 0 else Color.parseColor(if (isFill) fill else stroke)
    val isInverse = attributes.getValue("data-inverse-fill") == "true"
    val data = attributes.getValue("d")
    val path = MyPath().apply {
        readPathData(if (isInverse) data.substringAfter('Z').trim() else data)
        isInverseFill = isInverse
    }
    drawing.paths[path] = PaintOptions(
        color, attributes.getValue("stroke-width")?.toFloat() ?: 0f, isEraser, isFill
    )
}
