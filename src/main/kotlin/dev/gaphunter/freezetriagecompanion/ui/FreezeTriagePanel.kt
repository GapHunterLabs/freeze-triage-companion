package dev.gaphunter.freezetriagecompanion.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.actions.RevealFileAction
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import dev.gaphunter.freezetriagecompanion.history.FreezeEntry
import dev.gaphunter.freezetriagecompanion.history.FreezeHistory
import dev.gaphunter.freezetriagecompanion.report.FreezeReport
import dev.gaphunter.freezetriagecompanion.review.ReviewPrompt
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.nio.file.Path
import java.time.format.DateTimeFormatter
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * The "Freeze Triage" tool window: every freeze the IDE recorded, newest first, with what blocked the UI and where.
 *
 * Reading and classifying the dumps runs in a background task ([refresh]); only the table update runs on the EDT.
 * Nothing is sent anywhere: "Search YouTrack" opens a search in the browser only when the user clicks it.
 */
class FreezeTriagePanel(
    private val project: Project,
    parent: Disposable,
    private val logDirectories: () -> List<Path> = { defaultLogDirectories() },
    private val pluginsDir: () -> Path? = { runCatching { Path.of(PathManager.getPluginsPath()) }.getOrNull() },
) : Disposable {

    val component: JPanel = JPanel(BorderLayout())

    private val model = ListTableModel<FreezeEntry>(
        column("Started") { it.folder.started.format(WHEN) },
        column("Duration") { entry -> entry.folder.durationSeconds?.let { "$it s" } ?: "?" },
        column("Build") { it.folder.productBuild },
        column("Blocking call") { it.blocking.label },
        column("Where") { it.analysis?.where?.shortName ?: "" },
        column("Plugin in the stack") { entry -> entry.plugin?.second?.name ?: "" },
    )
    private val table = JBTable(model)
    private val details = JBTextArea()
    private var loading = false
    private var disposed = false

    init {
        Disposer.register(parent, this)
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.emptyText.text = "No freeze recorded"
        table.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) showDetails() }
        details.isEditable = false
        details.font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, details.font.size)
        details.text = "Select a freeze to see its report."

        val splitter = JBSplitter(true, 0.45f)
        splitter.firstComponent = JBScrollPane(table)
        splitter.secondComponent = JBScrollPane(details)

        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, buildActions(), true)
        toolbar.targetComponent = component
        component.border = JBUI.Borders.empty(2)
        component.add(toolbar.component, BorderLayout.NORTH)
        component.add(splitter, BorderLayout.CENTER)
        refresh()
    }

    val entries: List<FreezeEntry> get() = model.items

    fun selected(): FreezeEntry? = table.selectedRow.takeIf { it >= 0 }?.let { model.getItem(table.convertRowIndexToModel(it)) }

    /** Reads the log folders again in a background task. */
    fun refresh() {
        if (loading || project.isDisposed) return
        loading = true
        table.emptyText.text = "Reading freeze dumps…"
        object : Task.Backgroundable(project, "Reading IDE freeze dumps", true) {
            private var dirs: List<Path> = emptyList()
            private var result: List<FreezeEntry> = emptyList()

            // Listing the log folders is disk I/O too: it stays here, off the EDT, with the rest.
            override fun run(indicator: ProgressIndicator) {
                dirs = logDirectories()
                result = FreezeHistory.load(dirs, pluginsDir()) { indicator.checkCanceled() }
            }

            override fun onSuccess() = show(result, dirs)

            override fun onFinished() {
                loading = false
            }
        }.queue()
    }

    /** Puts [result] in the table. Runs on the EDT. */
    fun show(result: List<FreezeEntry>, dirs: List<Path>) {
        if (disposed) return
        model.items = result
        table.emptyText.text = if (dirs.isEmpty()) "No IDE log folder found" else "No freeze recorded in ${dirs.first()}"
        if (result.isNotEmpty()) table.selectionModel.setSelectionInterval(0, 0)
    }

    private fun showDetails() {
        val entry = selected()
        details.text = entry?.let { FreezeReport.markdown(it) } ?: "Select a freeze to see its report."
        details.caretPosition = 0
    }

    private fun buildActions(): DefaultActionGroup {
        val group = DefaultActionGroup()
        group.add(object : DumbAwareAction("Refresh", "Read the freeze folders again", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) = refresh()
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = !loading
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })
        group.add(object : ToggleAction("Include Other IDE Versions", "Also list freezes recorded by other installed versions of this IDE", AllIcons.Actions.ShowAsTree) {
            override fun isSelected(e: AnActionEvent) = includeOtherVersions()
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                PropertiesComponent.getInstance().setValue(KEY_OTHER_VERSIONS, state, true)
                refresh()
            }
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
        })
        group.addSeparator()
        group.add(entryAction("Copy Report", "Copy a Markdown report of the selected freeze", AllIcons.Actions.Copy) { entry ->
            CopyPasteManager.getInstance().setContents(StringSelection(FreezeReport.markdown(entry)))
            ReviewPrompt.recordHit(project, entry.folder.name)
        })
        group.add(entryAction("Search YouTrack", "Open a YouTrack search for tickets about the same method", AllIcons.Actions.Find, { it.analysis?.where != null }) { entry ->
            FreezeReport.youTrackSearchUrl(entry)?.let {
                BrowserUtil.browse(it)
                ReviewPrompt.recordHit(project, entry.folder.name)
            }
        })
        group.add(entryAction("Open Thread Dump", "Open the first thread dump of the selected freeze", AllIcons.Actions.MenuOpen, { it.dumpCount > 0 }) { entry ->
            // Listing the folder and finding the file in the VFS touch the disk: done on a pooled thread.
            val application = ApplicationManager.getApplication()
            application.executeOnPooledThread {
                val file = entry.folder.dumpFiles(1).firstOrNull() ?: return@executeOnPooledThread
                val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file) ?: return@executeOnPooledThread
                application.invokeLater({ FileEditorManager.getInstance(project).openFile(virtualFile, true) }, project.disposed)
            }
        })
        group.add(entryAction("Show Folder", "Show the freeze folder in the file manager", AllIcons.Actions.MenuOpen) { entry ->
            RevealFileAction.openDirectory(entry.folder.path.toFile())
        })
        return group
    }

    private fun entryAction(
        text: String,
        description: String,
        icon: javax.swing.Icon,
        enabled: (FreezeEntry) -> Boolean = { true },
        perform: (FreezeEntry) -> Unit,
    ): AnAction = object : DumbAwareAction(text, description, icon) {
        override fun actionPerformed(e: AnActionEvent) {
            selected()?.let(perform)
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selected()?.let(enabled) ?: false
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    override fun dispose() {
        disposed = true
    }

    companion object {
        private const val KEY_OTHER_VERSIONS = "dev.gaphunter.freezetriagecompanion.includeOtherVersions"
        private val WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        fun includeOtherVersions(): Boolean = PropertiesComponent.getInstance().getBoolean(KEY_OTHER_VERSIONS, true)

        fun defaultLogDirectories(): List<Path> {
            val log = runCatching { Path.of(PathManager.getLogPath()) }.getOrNull() ?: return emptyList()
            return FreezeHistory.logDirectories(log, includeOtherVersions())
        }

        private fun column(name: String, value: (FreezeEntry) -> String) = object : ColumnInfo<FreezeEntry, String>(name) {
            override fun valueOf(item: FreezeEntry): String = value(item)
        }
    }
}
