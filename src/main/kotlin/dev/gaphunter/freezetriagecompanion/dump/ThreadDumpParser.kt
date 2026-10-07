package dev.gaphunter.freezetriagecompanion.dump

/** One stack frame: `com.foo.Bar.method` plus the source location in parentheses (`Bar.kt:42`). */
data class DumpFrame(val method: String, val location: String) {
    override fun toString(): String = "$method($location)"

    /** `com.foo.Bar$Inner` for `com.foo.Bar$Inner.method`. */
    val className: String get() = method.substringBeforeLast('.', method)

    /** `Bar.method`, the short form JetBrains uses in freeze ticket titles. Drops `$1`, `$$Lambda/0x...` and the like. */
    val shortName: String
        get() {
            val simple = className.substringAfterLast('.').substringBefore('$')
            return "$simple.${method.substringAfterLast('.')}"
        }
}

data class DumpThread(val name: String, val state: String?, val frames: List<DumpFrame>) {
    val isEdt: Boolean get() = name.startsWith("AWT-EventQueue")
}

/**
 * Reads the thread section of a thread dump: the IDE's own `threadDump-*.txt` files and plain `jstack` output.
 *
 * Stops at the first `----------` line: the IDE appends coroutine, progress indicator and event count sections after
 * the threads, in a different format.
 */
object ThreadDumpParser {

    private val STATE = Regex("""^\s*java\.lang\.Thread\.State:\s*(\w+)""")
    private val FRAME = Regex("""^\s*at\s+(\S+?)\((.*)\)\s*$""")

    /** `java.base@25.0.3/`, `app//` and `java.desktop/` prefixes in front of the class name. */
    private val MODULE_PREFIX = Regex("""^(?:[\w.\-]+@[\w.\-]+/|[\w.\-]*//|(?:java|jdk)\.[\w.]+/)""")

    fun parse(text: String): List<DumpThread> {
        val threads = mutableListOf<DumpThread>()
        var name: String? = null
        var state: String? = null
        var frames = mutableListOf<DumpFrame>()

        fun flush() {
            name?.let { threads += DumpThread(it, state, frames) }
            name = null
            state = null
            frames = mutableListOf()
        }

        for (line in text.lineSequence()) {
            if (line.startsWith("----------")) break
            if (line.startsWith("\"")) {
                flush()
                name = threadName(line)
                continue
            }
            if (name == null) continue
            val frame = FRAME.find(line)
            if (frame != null) {
                val method = MODULE_PREFIX.replaceFirst(frame.groupValues[1], "")
                frames += DumpFrame(method, frame.groupValues[2])
                continue
            }
            if (state == null) STATE.find(line)?.let { state = it.groupValues[1] }
        }
        flush()
        return threads
    }

    /** `"AWT-EventQueue-0" prio=0 tid=0x0 nid=0x0 waiting on condition` gives `AWT-EventQueue-0`. */
    private fun threadName(header: String): String {
        val end = header.indexOf("\" ", 1).takeIf { it > 0 } ?: header.lastIndexOf('"').takeIf { it > 0 } ?: header.length
        return header.substring(1, end)
    }
}
