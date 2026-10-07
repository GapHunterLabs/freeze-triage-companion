package dev.gaphunter.freezetriagecompanion.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.gaphunter.freezetriagecompanion.Dumps
import dev.gaphunter.freezetriagecompanion.history.FreezeHistory
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class FreezeTriagePanelTest : BasePlatformTestCase() {

    fun testActionIsRegisteredUnderDiagnosticTools() {
        val manager = ActionManager.getInstance()
        val action = manager.getAction("FreezeTriageCompanion.Show")
        assertNotNull(action)
        // The group may still hold the action's stub, so compare ids, not instances.
        val group = manager.getAction("HelpDiagnosticTools") as DefaultActionGroup
        val ids = group.getChildActionsOrStubs().map { manager.getId(it) }
        assertTrue("HelpDiagnosticTools children: $ids", "FreezeTriageCompanion.Show" in ids)
    }

    fun testPanelShowsFreezesAndTheReportOfTheSelectedOne() {
        val log = Files.createTempDirectory("freeze-triage").resolve("IntelliJIdea2026.2/log").createDirectories()
        log.resolve("threadDumps-freeze-20260920-170950-IU-262.8665.337-CreateFile0-9sec").createDirectories()
            .resolve("threadDump-20260920-170953.txt").writeText(Dumps.EDT_IO)
        val disposable = Disposer.newDisposable()
        try {
            val panel = FreezeTriagePanel(project, disposable, logDirectories = { emptyList() }, pluginsDir = { null })
            panel.show(FreezeHistory.load(listOf(log), null), listOf(log))

            assertEquals(1, panel.entries.size)
            val selected = panel.selected()!!
            assertEquals("File I/O", selected.blocking.label)
            assertEquals("ConfigLoader.load", selected.analysis!!.where!!.shortName)
        } finally {
            Disposer.dispose(disposable)
        }
    }
}
