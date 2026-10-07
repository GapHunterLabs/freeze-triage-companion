package dev.gaphunter.freezetriagecompanion.dump

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * One `threadDumps-freeze-*` folder written by the IDE when the UI freezes.
 *
 * The folder name carries the start time, the product code and the build. Once the freeze is over, the IDE renames
 * the folder with the top EDT frame and the duration, for example
 * `threadDumps-freeze-20260920-170950-IU-262.8665.337-Unsafe.park-7sec`. A folder without that suffix is a freeze
 * that never finished normally (the IDE was closed or killed while frozen).
 */
data class FreezeFolder(
    val path: Path,
    val started: LocalDateTime,
    val product: String,
    val build: String,
    val topFrame: String?,
    val durationSeconds: Int?,
) {
    val name: String get() = path.name

    /** `IU-262.8665.337`, the form YouTrack uses for affected builds. */
    val productBuild: String get() = "$product-$build"

    /** The `threadDump-*.txt` files inside, oldest first, at most [limit]. */
    fun dumpFiles(limit: Int = MAX_DUMPS_PER_FREEZE): List<Path> =
        runCatching {
            Files.list(path).use { files ->
                files.filter { it.isRegularFile() && it.name.startsWith("threadDump") && it.name.endsWith(".txt") }
                    .sorted()
                    .limit(limit.toLong())
                    .toList()
            }
        }.getOrDefault(emptyList())

    companion object {
        /** A long freeze writes a dump every few seconds; the first ones are enough to classify it. */
        const val MAX_DUMPS_PER_FREEZE = 5

        private val NAME = Regex(
            """^threadDumps-freeze-(\d{8}-\d{6})-([A-Z]{2,4})-(\d+(?:\.(?:\d+|SNAPSHOT))*)(?:-(.+)-(\d+)sec)?$"""
        )
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        fun parse(path: Path): FreezeFolder? {
            val match = NAME.matchEntire(path.fileName?.toString() ?: return null) ?: return null
            val (stamp, product, build, frame, seconds) = match.destructured
            val started = runCatching { LocalDateTime.parse(stamp, STAMP) }.getOrNull() ?: return null
            return FreezeFolder(path, started, product, build, frame.ifEmpty { null }, seconds.toIntOrNull())
        }
    }
}
