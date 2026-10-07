package dev.gaphunter.freezetriagecompanion

/** Builds thread dumps in the format the IDE writes to `threadDump-*.txt`. */
object Dumps {

    fun thread(name: String, state: String, vararg frames: String): String = buildString {
        appendLine("\"$name\" prio=0 tid=0x0 nid=0x0 ${state.lowercase()}")
        appendLine("     java.lang.Thread.State: $state")
        frames.forEach { appendLine("\tat $it") }
        appendLine()
    }

    fun dump(vararg threads: String): String = threads.joinToString("") +
        "---------- Coroutine dump (stripped) ----------\n" +
        "\"Main toolbar update\":StandaloneCoroutine{Active}, state: SUSPENDED\n" +
        "\tat com.intellij.coroutine.Fake.frame(Fake.kt:1)\n"

    /** EDT busy reading a file inside a plugin action. */
    val EDT_IO = dump(
        thread(
            "AWT-EventQueue-0", "RUNNABLE",
            "java.base@25.0.3/sun.nio.fs.WindowsNativeDispatcher.CreateFile0(Native Method)",
            "java.base@25.0.3/sun.nio.fs.WindowsChannelFactory.open(WindowsChannelFactory.java:308)",
            "java.base@25.0.3/java.nio.file.Files.readAllBytes(Files.java:3299)",
            "com.acme.slowplugin.ConfigLoader.load(ConfigLoader.kt:42)",
            "com.acme.slowplugin.RefreshAction.actionPerformed(RefreshAction.kt:17)",
            "com.intellij.openapi.actionSystem.ex.ActionUtil.performAction(ActionUtil.kt:200)",
            "java.desktop@25.0.3/java.awt.EventDispatchThread.run(EventDispatchThread.java:92)",
        ),
        thread("Common-Cleaner", "TIMED_WAITING", "java.base@25.0.3/jdk.internal.misc.Unsafe.park(Native Method)"),
    )

    /** EDT waiting for the write permit while a pooled thread runs a process inside a read action. */
    val LOCK_WAIT = dump(
        thread(
            "AWT-EventQueue-0", "WAITING",
            "java.base@25.0.3/jdk.internal.misc.Unsafe.park(Native Method)",
            "java.base@25.0.3/java.util.concurrent.locks.LockSupport.park(LockSupport.java:369)",
            "com.intellij.openapi.application.impl.ReadMostlyRWLock.writeLock(ReadMostlyRWLock.java:200)",
            "com.intellij.openapi.application.impl.AnyThreadWriteThreadingSupport.getWritePermit(AnyThreadWriteThreadingSupport.kt:500)",
            "com.intellij.openapi.application.impl.AnyThreadWriteThreadingSupport.runWriteAction(AnyThreadWriteThreadingSupport.kt:600)",
            "com.intellij.openapi.application.impl.ApplicationImpl.runWriteAction(ApplicationImpl.java:900)",
            "com.intellij.openapi.command.WriteCommandAction.run(WriteCommandAction.java:10)",
        ),
        thread(
            "ApplicationImpl pooled thread 7", "WAITING",
            "java.base@25.0.3/jdk.internal.misc.Unsafe.park(Native Method)",
            "com.intellij.openapi.application.impl.ComputationState.acquireReadPermit(ComputationState.kt:80)",
            "com.intellij.openapi.application.impl.AnyThreadWriteThreadingSupport.getReadPermit(AnyThreadWriteThreadingSupport.kt:300)",
            "com.intellij.openapi.application.impl.AnyThreadWriteThreadingSupport.runReadAction(AnyThreadWriteThreadingSupport.kt:310)",
        ),
        thread(
            "ApplicationImpl pooled thread 3", "RUNNABLE",
            "java.base@25.0.3/java.lang.ProcessImpl.waitForSingleObject(Native Method)",
            "java.base@25.0.3/java.lang.ProcessImpl.waitFor(ProcessImpl.java:634)",
            "com.intellij.execution.process.CapturingProcessHandler.runProcess(CapturingProcessHandler.java:50)",
            "com.intellij.packaging.impl.PackageFileWorker.packFile(PackageFileWorker.java:120)",
            "com.intellij.openapi.application.impl.AnyThreadWriteThreadingSupport.runReadAction(AnyThreadWriteThreadingSupport.kt:310)",
            "com.intellij.openapi.application.impl.ApplicationImpl.runReadAction(ApplicationImpl.java:800)",
            "com.intellij.openapi.application.ReadAction.run(ReadAction.java:20)",
        ),
    )

    /** EDT idle in a modal dialog's event loop, like a dump taken after the freeze ended. */
    val IDLE = dump(
        thread(
            "AWT-EventQueue-0", "WAITING",
            "java.base@25.0.3/jdk.internal.misc.Unsafe.park(Native Method)",
            "java.base@25.0.3/java.util.concurrent.locks.LockSupport.park(LockSupport.java:369)",
            "java.base@25.0.3/java.util.concurrent.locks.AbstractQueuedSynchronizer\$ConditionObject.await(AbstractQueuedSynchronizer.java:1752)",
            "java.desktop@25.0.3/java.awt.EventQueue.getNextEvent(EventQueue.java:561)",
            "com.intellij.ide.IdeEventQueue.getNextEvent(IdeEventQueue.kt:454)",
            "java.desktop@25.0.3/java.awt.Dialog.show(Dialog.java:1051)",
        ),
    )
}
