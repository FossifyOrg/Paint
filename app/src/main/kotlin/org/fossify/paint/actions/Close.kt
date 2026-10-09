package org.fossify.paint.actions

import android.graphics.Path
import java.io.Writer

class Close : Action {
    override fun perform(path: Path) {
        path.close()
    }

    override fun perform(writer: Writer) {
        writer.write("Z")
    }
}
