package dev.gaphunter.freezetriagecompanion.history

import dev.gaphunter.freezetriagecompanion.Dumps
import dev.gaphunter.freezetriagecompanion.attribution.PluginIndex
import dev.gaphunter.freezetriagecompanion.classify.BlockingClass
import dev.gaphunter.freezetriagecompanion.report.FreezeReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class FreezeHistoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun root(): Path = temp.root.toPath()

    private fun freeze(logDir: Path, name: String, vararg dumps: String): Path {
        val folder = logDir.resolve(name).createDirectories()
        dumps.forEachIndexed { i, text -> folder.resolve("threadDump-20260101-00000$i.txt").writeText(text) }
        return folder
    }

    /** A user-installed plugin: `<plugins>/<dir>/lib/<dir>.jar` with a plugin.xml and the given classes. */
    private fun plugin(pluginsDir: Path, dir: String, xml: String, vararg classes: String) {
        val lib = pluginsDir.resolve(dir).resolve("lib").createDirectories()
        ZipOutputStream(Files.newOutputStream(lib.resolve("$dir.jar"))).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/plugin.xml"))
            zip.write(xml.toByteArray())
            zip.closeEntry()
            classes.forEach {
                zip.putNextEntry(ZipEntry(it))
                zip.closeEntry()
            }
        }
    }

    @Test
    fun `log folders of other versions of the same IDE are included, other IDEs are not`() {
        val jetbrains = root().resolve("JetBrains")
        val current = jetbrains.resolve("IntelliJIdea2026.2/log").createDirectories()
        jetbrains.resolve("IntelliJIdea2025.2/log").createDirectories()
        jetbrains.resolve("PyCharm2026.2/log").createDirectories()
        jetbrains.resolve("IntelliJIdea2024.3").createDirectories() // no log folder

        val all = FreezeHistory.logDirectories(current, includeOtherVersions = true).map { root().relativize(it).toString().replace('\\', '/') }
        assertEquals(listOf("JetBrains/IntelliJIdea2026.2/log", "JetBrains/IntelliJIdea2025.2/log"), all)
        assertEquals(listOf(current), FreezeHistory.logDirectories(current, includeOtherVersions = false))
    }

    @Test
    fun `macOS layout keeps logs directly in the version folder`() {
        val logs = root().resolve("Logs/JetBrains")
        val current = logs.resolve("IntelliJIdea2026.2").createDirectories()
        logs.resolve("IntelliJIdea2025.3").createDirectories()
        assertEquals(2, FreezeHistory.logDirectories(current, includeOtherVersions = true).size)
        assertEquals("IntelliJIdea", FreezeHistory.productOf("IntelliJIdea2026.2"))
        assertEquals("", FreezeHistory.productOf("log"))
    }

    @Test
    fun `load classifies each freeze, newest first, and names the user-installed plugin in the stack`() {
        val log = root().resolve("IntelliJIdea2026.2/log").createDirectories()
        freeze(log, "threadDumps-freeze-20260920-170950-IU-262.8665.337-ConfigLoader.load-9sec", Dumps.EDT_IO, Dumps.EDT_IO)
        freeze(log, "threadDumps-freeze-20260922-103730-IU-262.8665.337")
        freeze(log, "threadDumps-freeze-20260801-120000-IU-262.8665.337-Unsafe.park-6sec", Dumps.LOCK_WAIT)
        Files.createDirectories(log.resolve("not-a-freeze"))

        val plugins = root().resolve("plugins")
        plugin(
            plugins, "slow-plugin",
            "<idea-plugin><id>com.acme.slow</id><name>Slow Plugin</name><version>1.2.0</version><vendor url=\"https://acme.example\">ACME</vendor></idea-plugin>",
            "com/acme/slowplugin/ConfigLoader.class", "com/acme/slowplugin/RefreshAction.class",
        )
        plugin(plugins, "other", "<idea-plugin><id>org.other</id><name>Other</name></idea-plugin>", "org/other/Thing.class")

        val entries = FreezeHistory.load(listOf(log), plugins)
        assertEquals(listOf("20260922", "20260920", "20260801"), entries.map { it.folder.name.substring(19, 27) })

        val unfinished = entries[0]
        assertEquals(0, unfinished.dumpCount)
        assertNull(unfinished.analysis)
        assertEquals("UI freeze (no thread dump captured)", FreezeReport.title(unfinished))

        val io = entries[1]
        assertEquals(2, io.dumpCount)
        assertEquals(BlockingClass.IO, io.blocking)
        assertEquals("com.acme.slow", io.plugin!!.second.id)
        assertEquals("Slow Plugin 1.2.0 by ACME", io.plugin!!.second.toString())
        assertEquals("com.acme.slowplugin.ConfigLoader.load", io.plugin!!.first.method)
        assertEquals("IntelliJIdea2026.2", io.ideDirectory)

        val lock = entries[2]
        assertEquals(BlockingClass.LOCK_WAIT, lock.blocking)
        assertNull("platform freeze: no user-installed plugin in the stack", lock.plugin)
    }

    @Test
    fun `plugin index keeps only the packages asked for and ignores folders without plugin xml`() {
        val plugins = root().resolve("plugins")
        plugin(plugins, "a", "<idea-plugin><name>Only Name</name></idea-plugin>", "com/a/X.class", "com/a/sub/Y.class")
        plugins.resolve("not-a-plugin").createDirectories()
        val index = PluginIndex.build(plugins, setOf("com.a.sub", "com.missing"))
        assertEquals(1, index.size)
        assertEquals("com.a.sub", PluginIndex.packageOf("com.a.sub.Y\$Inner.run"))
        assertEquals("com.a", PluginIndex.packageOf("com.a.X\$\$Lambda/0x01.run"))
        assertEquals("", PluginIndex.packageOf("main"))
        assertEquals(PluginIndex.EMPTY.size, PluginIndex.build(null, setOf("com.a")).size)
        assertEquals("Only Name", PluginIndex.ownerFrom("<idea-plugin><name>Only Name</name></idea-plugin>")!!.id)
    }

    @Test
    fun `report has the facts, both stacks for a lock wait, and no file paths`() {
        val log = root().resolve("IntelliJIdea2026.2/log").createDirectories()
        freeze(log, "threadDumps-freeze-20260801-120000-IU-262.8665.337-Unsafe.park-6sec", Dumps.LOCK_WAIT)
        val entry = FreezeHistory.load(listOf(log), null).single()

        val report = FreezeReport.markdown(entry)
        assertTrue(report, report.startsWith("### Freeze: EDT waits for the lock held by CapturingProcessHandler.runProcess"))
        assertTrue(report.contains("| Duration | 6 s |"))
        assertTrue(report.contains("| IDE build | IU-262.8665.337 |"))
        assertTrue(report.contains("Lock holder stack (top 7 of 7 frames):"))
        assertTrue(report.contains("EDT stack (top 7 of 7 frames):"))
        assertFalse("no local paths in the report", report.contains(root().toString()))

        val url = FreezeReport.youTrackSearchUrl(entry)
        assertNotNull(url)
        assertEquals("https://youtrack.jetbrains.com/issues?q=%22CapturingProcessHandler.runProcess%22", url)
    }

    @Test
    fun `report leaves out thread names and frame locations that carry a path`() {
        assertEquals("a background thread", FreezeReport.threadLabel("Indexing C:\\Users\\someone\\project"))
        assertEquals("a background thread", FreezeReport.threadLabel("Scanning /home/someone/project"))
        assertEquals("thread \"ApplicationImpl pooled thread 3\"", FreezeReport.threadLabel("ApplicationImpl pooled thread 3"))

        val log = root().resolve("log").createDirectories()
        val dump = Dumps.dump(
            Dumps.thread(
                "AWT-EventQueue-0", "RUNNABLE",
                "java.base@25.0.3/java.io.FileInputStream.readBytes(Native Method)",
                "Script1.run(C:\\Users\\someone\\project\\build.gradle:12)",
            )
        )
        freeze(log, "threadDumps-freeze-20260801-120000-IU-262.1-FileInputStream.readBytes-5sec", dump)
        val report = FreezeReport.markdown(FreezeHistory.load(listOf(log), null).single())
        assertTrue(report, report.contains("at Script1.run(build.gradle:12)"))
        assertFalse(report, report.contains("someone"))
    }

    @Test
    fun `dominant analysis prefers informative dumps over idle ones`() {
        val log = root().resolve("log").createDirectories()
        freeze(log, "threadDumps-freeze-20260801-120000-IU-262.1-Unsafe.park-6sec", Dumps.IDLE, Dumps.EDT_IO, Dumps.IDLE)
        val entry = FreezeHistory.load(listOf(log), null).single()
        assertEquals(3, entry.dumpCount)
        assertEquals(BlockingClass.IO, entry.blocking)
    }
}
