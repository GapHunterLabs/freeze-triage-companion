package dev.gaphunter.freezetriagecompanion.history

import dev.gaphunter.freezetriagecompanion.report.FreezeReport
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Optional check against the freeze folders of a real IDE: set `FREEZE_TRIAGE_LOG_DIR` to an IDE log folder (for
 * example `%LOCALAPPDATA%\JetBrains\IntelliJIdea2026.2\log`). Skipped when the variable is not set.
 */
class LocalLogsCheckTest {

    @Test
    fun `real freeze folders are read and every dump is classified`() {
        val dir = System.getenv("FREEZE_TRIAGE_LOG_DIR")?.let { Path.of(it) }
        assumeTrue("FREEZE_TRIAGE_LOG_DIR not set", dir != null && Files.isDirectory(dir))
        val logDirs = FreezeHistory.logDirectories(dir!!, includeOtherVersions = true)
        val entries = FreezeHistory.load(logDirs, null)
        println("[FREEZE TRIAGE] ${logDirs.size} log folders, ${entries.size} freezes")
        for (entry in entries) {
            println("[FREEZE TRIAGE] ${entry.ideDirectory} | ${entry.folder.name} | dumps=${entry.dumpCount} | ${FreezeReport.title(entry)}")
            if (entry.dumpCount > 0) assertTrue(entry.folder.name, entry.analysis != null)
        }
    }
}
