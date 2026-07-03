package org.fossify.paint.actions

import android.graphics.Matrix
import android.graphics.Path
import java.io.Serializable
import java.io.Writer

interface Action : Serializable {
    fun perform(path: Path)

    fun perform(writer: Writer, transform: Matrix)
}
