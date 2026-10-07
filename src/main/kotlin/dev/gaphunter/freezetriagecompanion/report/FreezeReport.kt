package dev.gaphunter.freezetriagecompanion.report

import dev.gaphunter.freezetriagecompanion.classify.BlockingClass
import dev.gaphunter.freezetriagecompanion.dump.DumpThread
import dev.gaphunter.freezetriagecompanion.history.FreezeEntry
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.format.DateTimeFormatter

/**
 * Builds the text a user pastes into an issue: a title in the form JetBrains uses for freeze tickets, the facts from
 * the folder name, the classification and the trimmed stacks.
 *
 * Only stack frames, the IDE build and the lock holder's thread name go into the report: no file paths, project
 * names or other thread names.
 */
object FreezeReport {

    private const val STACK_FRAMES = 40
    private val WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun title(entry: FreezeEntry): String {
        val analysis = entry.analysis ?: return "UI freeze (no thread dump captured)"
        val where = analysis.where?.shortName
        return when {
            analysis.blocking == BlockingClass.IDLE -> "UI freeze (EDT idle when sampled)"
            analysis.lockHolder != null && where != null ->
                "Freeze: EDT waits for the lock held by $where (${analysis.holderBlocking?.label ?: "unknown"})"
            where != null -> "Freeze in $where on EDT (${analysis.blocking.label})"
            else -> "UI freeze (${analysis.blocking.label})"
        }
    }

    fun markdown(entry: FreezeEntry): String = buildString {
        val folder = entry.folder
        val analysis = entry.analysis
        appendLine("### ${title(entry)}")
        appendLine()
        appendLine("| | |")
        appendLine("|---|---|")
        appendLine("| Started | ${folder.started.format(WHEN)} |")
        appendLine("| Duration | ${folder.durationSeconds?.let { "$it s" } ?: "unknown (the freeze did not end normally)"} |")
        appendLine("| IDE build | ${folder.productBuild} |")
        appendLine("| Blocking call | ${analysis?.blocking?.label ?: BlockingClass.OTHER.label} |")
        analysis?.where?.let { appendLine("| Where | `${it.method}` |") }
        analysis?.lockHolder?.let { holder ->
            appendLine("| Lock holder | ${threadLabel(holder.name)}: ${analysis.holderBlocking?.label ?: "unknown"} |")
        }
        entry.plugin?.let { (frame, owner) ->
            appendLine("| Plugin in the stack | $owner (`${owner.id}`), at `${frame.method}` |")
        }
        appendLine("| Thread dumps | ${entry.dumpCount} |")
        if (analysis == null) return@buildString
        analysis.lockHolder?.let { appendStack("Lock holder stack", it) }
        analysis.edt?.let { appendStack("EDT stack", it) }
    }

    /** Some thread names carry a path (a task named after a folder): those are left out of the report. */
    internal fun threadLabel(name: String): String =
        if ('/' in name || '\\' in name) "a background thread" else "thread \"$name\""

    private fun StringBuilder.appendStack(heading: String, thread: DumpThread) {
        appendLine()
        appendLine("$heading (top ${minOf(STACK_FRAMES, thread.frames.size)} of ${thread.frames.size} frames):")
        appendLine("```")
        // A script frame can carry a full path as its location (`Script.run(C:\...\build.gradle:12)`): keep the file name.
        thread.frames.take(STACK_FRAMES).forEach {
            appendLine("at ${it.method}(${it.location.substringAfterLast('/').substringAfterLast('\\')})")
        }
        appendLine("```")
    }

    /** A YouTrack search for tickets that mention the same method, fixed ones included. */
    fun youTrackSearchUrl(entry: FreezeEntry): String? {
        val where = entry.analysis?.where?.shortName ?: return null
        val query = "\"$where\""
        return "https://youtrack.jetbrains.com/issues?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
    }
}
