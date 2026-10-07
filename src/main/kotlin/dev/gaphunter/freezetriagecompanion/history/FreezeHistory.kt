package dev.gaphunter.freezetriagecompanion.history

import dev.gaphunter.freezetriagecompanion.attribution.PluginIndex
import dev.gaphunter.freezetriagecompanion.attribution.PluginOwner
import dev.gaphunter.freezetriagecompanion.classify.BlockingClass
import dev.gaphunter.freezetriagecompanion.classify.DumpAnalysis
import dev.gaphunter.freezetriagecompanion.classify.FreezeClassifier
import dev.gaphunter.freezetriagecompanion.dump.DumpFrame
import dev.gaphunter.freezetriagecompanion.dump.FreezeFolder
import dev.gaphunter.freezetriagecompanion.dump.ThreadDumpParser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * One freeze: its folder, the analysis of its dumps and, when the blocking stack runs through a user-installed
 * plugin, that plugin.
 *
 * [analysis] is the most frequent result among the dumps (a long freeze writes several). It is null when the IDE
 * wrote no dump for the freeze.
 */
data class FreezeEntry(
    val folder: FreezeFolder,
    val ideDirectory: String,
    val dumpCount: Int,
    val analysis: DumpAnalysis?,
    val plugin: Pair<DumpFrame, PluginOwner>?,
) {
    val blocking: BlockingClass get() = analysis?.blocking ?: BlockingClass.OTHER
}

object FreezeHistory {

    /**
     * The log folders to read: [currentLog], plus the log folders of other versions of the same IDE when
     * [includeOtherVersions] is set.
     *
     * Windows and Linux keep logs in `<IDE><version>/log` (`IntelliJIdea2026.2/log`); macOS keeps them directly in
     * `Logs/JetBrains/<IDE><version>`. Both layouts are handled.
     */
    fun logDirectories(currentLog: Path, includeOtherVersions: Boolean): List<Path> {
        if (!includeOtherVersions) return listOf(currentLog)
        val nested = currentLog.name == "log"
        val versionDir = (if (nested) currentLog.parent else currentLog) ?: return listOf(currentLog)
        val root = versionDir.parent ?: return listOf(currentLog)
        val product = productOf(versionDir.name)
        if (product.isEmpty()) return listOf(currentLog)
        val siblings = runCatching {
            Files.list(root).use { dirs ->
                dirs.filter { it.isDirectory() && productOf(it.name) == product }
                    .map { if (nested) it.resolve("log") else it }
                    .filter { it.isDirectory() }
                    .sorted()
                    .toList()
            }
        }.getOrDefault(emptyList())
        return (listOf(currentLog) + siblings).distinctBy { it.toAbsolutePath().normalize() }
    }

    /** `IntelliJIdea` for `IntelliJIdea2026.2`; empty when the name carries no version. */
    internal fun productOf(directoryName: String): String {
        val match = Regex("""^(.*?)(\d{4}\.\d+(?:\.\d+)?)$""").matchEntire(directoryName) ?: return ""
        return match.groupValues[1]
    }

    /** Every freeze folder in [logDirectories], newest first. */
    fun findFolders(logDirectories: List<Path>): List<FreezeFolder> =
        logDirectories.flatMap { dir ->
            runCatching {
                Files.list(dir).use { entries ->
                    entries.filter { it.isDirectory() }.toList().mapNotNull { FreezeFolder.parse(it) }
                }
            }.getOrDefault(emptyList())
        }.sortedByDescending { it.started }

    /**
     * Reads and classifies each freeze, then attributes it with the plugins found in [pluginsDir].
     * [checkCanceled] is called between files so a background task can stop early.
     */
    fun load(logDirectories: List<Path>, pluginsDir: Path?, checkCanceled: () -> Unit = {}): List<FreezeEntry> {
        val analyzed = findFolders(logDirectories).map { folder ->
            checkCanceled()
            val analyses = folder.dumpFiles().mapNotNull { file ->
                checkCanceled()
                runCatching { FreezeClassifier.analyze(ThreadDumpParser.parse(file.readText())) }.getOrNull()
            }
            Triple(folder, analyses.size, dominant(analyses))
        }
        val packages = analyzed.flatMap { (_, _, analysis) ->
            analysis?.thread?.frames.orEmpty().map { PluginIndex.packageOf(it.method) }
        }.filter { it.isNotEmpty() }.toSet()
        val index = PluginIndex.build(pluginsDir, packages, checkCanceled)
        return analyzed.map { (folder, count, analysis) ->
            val plugin = analysis?.thread?.let { index.firstPluginFrame(it.frames) }
            FreezeEntry(folder, ideDirectoryOf(folder), count, analysis, plugin)
        }
    }

    /** The most frequent (blocking class, where) pair; on a tie, the earliest dump. */
    internal fun dominant(analyses: List<DumpAnalysis>): DumpAnalysis? {
        if (analyses.isEmpty()) return null
        val informative = analyses.filter { it.blocking != BlockingClass.IDLE && it.blocking != BlockingClass.OTHER }
            .ifEmpty { analyses }
        val counts = informative.groupingBy { it.blocking to it.where?.method }.eachCount()
        val best = counts.values.max()
        return informative.first { counts[it.blocking to it.where?.method] == best }
    }

    /** `IntelliJIdea2026.2` for a folder in `.../IntelliJIdea2026.2/log/threadDumps-freeze-...`. */
    private fun ideDirectoryOf(folder: FreezeFolder): String {
        val logDir = folder.path.parent ?: return ""
        return (if (logDir.name == "log") logDir.parent else logDir)?.name ?: ""
    }
}
