package org.fossify.paint.models

import android.graphics.Path
import org.fossify.paint.actions.Action
import org.fossify.paint.actions.Close
import org.fossify.paint.actions.Line
import org.fossify.paint.actions.Move
import org.fossify.paint.actions.Quad
import java.io.ObjectInputStream
import java.io.Serializable
import java.security.InvalidParameterException
import java.util.LinkedList

// https://stackoverflow.com/a/8127953
class MyPath : Path(), Serializable {
    val actions = LinkedList<Action>()
    var isInverseFill = false
        set(value) {
            field = value
            fillType = if (value) FillType.INVERSE_EVEN_ODD else FillType.EVEN_ODD
        }

    init {
        fillType = FillType.EVEN_ODD
    }

    private fun readObject(inputStream: ObjectInputStream) {
        inputStream.defaultReadObject()

        val copiedActions = actions.map { it }
        copiedActions.forEach {
            it.perform(this)
        }
    }

    fun readPathData(pathData: String) {
        val tokens = pathData.split("\\s+".toRegex()).filter(String::isNotEmpty)
        var i = 0
        while (i < tokens.size) {
            when (tokens[i][0]) {
                'M' -> addAction(Move(tokens[i]))
                'L' -> addAction(Line(tokens[i]))
                'Z', 'z' -> close()
                'Q' -> {
                    if (i + 1 >= tokens.size) {
                        throw InvalidParameterException("Error parsing the data for a Quad.")
                    }
                    addAction(Quad(tokens[i] + " " + tokens[i + 1]))
                    ++i
                }
            }
            ++i
        }
    }

    override fun reset() {
        actions.clear()
        super.reset()
    }

    private fun addAction(action: Action) {
        when (action) {
            is Close -> close()
            is Move -> moveTo(action.x, action.y)
            is Line -> lineTo(action.x, action.y)
            is Quad -> quadTo(action.x1, action.y1, action.x2, action.y2)
        }
    }

    override fun close() {
        actions.add(Close())
        super.close()
    }

    override fun moveTo(x: Float, y: Float) {
        actions.add(Move(x, y))
        super.moveTo(x, y)
    }

    override fun lineTo(x: Float, y: Float) {
        actions.add(Line(x, y))
        super.lineTo(x, y)
    }

    override fun quadTo(x1: Float, y1: Float, x2: Float, y2: Float) {
        actions.add(Quad(x1, y1, x2, y2))
        super.quadTo(x1, y1, x2, y2)
    }
}
