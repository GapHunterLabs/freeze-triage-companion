package dev.gaphunter.freezetriagecompanion.classify

import dev.gaphunter.freezetriagecompanion.dump.DumpFrame
import dev.gaphunter.freezetriagecompanion.dump.DumpThread

/** What the frozen thread was doing, by its first meaningful frame from the top. */
enum class BlockingClass(val id: String, val label: String) {
    PROCESS("process", "Starting or waiting for an external process"),
    NETWORK("network", "Network call"),
    CLASSLOADING("classloading", "Class loading"),
    RUN_BLOCKING("run_blocking", "runBlocking on a coroutine"),
    WAIT_OTHER_THREAD("wait_other_thread", "Waiting for another thread"),
    SERVICE_INIT("service_init", "Service or extension initialization"),
    ICON_PROVIDER("icon_provider", "File icon computation"),
    IMAGE("image", "Image or icon loading"),
    VFS_REFRESH("vfs_refresh", "Virtual file system refresh"),
    VFS_LOOKUP("vfs_lookup", "Virtual file system lookup"),
    IO("io", "File I/O"),
    INDEX("index", "Index access"),
    SMART_POINTER("smart_pointer", "Smart pointer restore"),
    PSI_RESOLVE("psi_resolve", "Reference resolution or PSI loading"),
    LOCK_WAIT("lock_wait", "Waiting for the read/write lock"),
    UI_INIT("ui_init", "Creating Settings or tool window UI"),
    PSI_VISIT("psi_visit", "Walking the PSI tree"),
    CPU("cpu", "Computation (no blocking call on top)"),
    IDLE("idle", "EDT was idle when sampled"),
    OTHER("other", "No stack captured"),
}

/**
 * The result for one dump.
 *
 * [thread] is the thread whose stack explains the freeze: the EDT, or the thread holding the lock the EDT waits
 * for. [where] is its first frame outside the JDK, Kotlin and the locking infrastructure.
 */
data class DumpAnalysis(
    val blocking: BlockingClass,
    val edt: DumpThread?,
    val lockHolder: DumpThread?,
    val holderBlocking: BlockingClass?,
    val where: DumpFrame?,
) {
    val thread: DumpThread? get() = lockHolder ?: edt
}

/**
 * Classifies freezes by the blocking call on top of the frozen stack.
 *
 * The frame patterns were derived from 859 public YouTrack freeze tickets (IJPL and IDEA, 2025-2026) and are checked
 * against 393 of them that carry a stack (see `youtrack_stacks.tsv` in the tests).
 */
object FreezeClassifier {

    /** Order matters: for each frame, the first class whose pattern matches wins. */
    private val LEAVES: List<Pair<BlockingClass, Regex>> = listOf(
        BlockingClass.PROCESS to Regex(
            """java\.lang\.ProcessImpl|ProcessBuilder\.start|GeneralCommandLine\.createProcess|forkAndExec|""" +
                """ProcessHandle|\.waitFor\(|CapturingProcess|ExecUtil"""
        ),
        BlockingClass.NETWORK to Regex("""java\.net\.|sun\.nio\.ch\.(Socket|Net)|NioSocketImpl|HttpURLConnection|HttpClient|SSLSocket"""),
        BlockingClass.CLASSLOADING to Regex(
            """ClassLoader\.loadClass|PathClassLoader|UrlClassLoader|defineClass|ImmutableZipFile|""" +
                """ZipFile|ClassPath\.findResource"""
        ),
        BlockingClass.RUN_BLOCKING to Regex("""BlockingCoroutine|runBlocking|joinBlocking|runBlockingCancellable|runBlockingMaybeCancellable"""),
        BlockingClass.WAIT_OTHER_THREAD to Regex(
            """BackgroundTaskUtil|FutureTask\.(get|awaitDone)|CompletableFuture\.(get|join|waitingGet)|""" +
                """awaitWithCheckCanceled|CountDownLatch\.await|Semaphore\.waitFor|""" +
                """EnvironmentUtil\.getEnvironmentMap|invokeAndWait"""
        ),
        BlockingClass.SERVICE_INIT to Regex(
            """getOrCreateInstanceBlocking|instantiateClass|createExtensionInstance|""" +
                """ExtensionPointImpl\.(getExtensionList|processWithPluginDescriptor|createExtensionInstances)|""" +
                """ComponentManagerImpl\.(doGetService|instantiate)"""
        ),
        BlockingClass.ICON_PROVIDER to Regex(
            """IconProvider\.getIcon|getIconFromProviders|FileIconUtil|computeFileIcon|IconUtil\.computeBaseFileIcon"""
        ),
        BlockingClass.IMAGE to Regex("""javax\.imageio|ImageIO|IconLoader|ImageLoader|svg|JSVG|ImageDataByPathLoader"""),
        BlockingClass.VFS_REFRESH to Regex(
            """RefreshQueue|RefreshSession|LocalFileSystem.*refresh|markDirtyAndRefresh|refreshAndFind|""" +
                """VirtualFileManager.*syncRefresh"""
        ),
        BlockingClass.VFS_LOOKUP to Regex(
            """VirtualDirectoryImpl\.(findChild|findIndexByName|findInCachedChildren)|findFileByUrl|""" +
                """findFileByPath|PersistentFSImpl\.(findChild|list)"""
        ),
        BlockingClass.IO to Regex(
            """java\.io\.(FileInputStream|FileOutputStream|RandomAccessFile|File\.(exists|list|isDirectory|length|""" +
                """lastModified|canRead|getCanonical))|sun\.nio\.fs|java\.nio\.file\.Files|WindowsNativeDispatcher|""" +
                """UnixNativeDispatcher|FileChannelImpl|FileDispatcherImpl|NioFiles"""
        ),
        BlockingClass.INDEX to Regex(
            """FileBasedIndex|StubIndex|PersistentHashMap|PersistentEnumerator|DurableDataEnumerator|""" +
                """IndexStorage|PersistentMapImpl"""
        ),
        BlockingClass.SMART_POINTER to Regex("""SmartPsiElementPointer|SmartPointerManager|restoreElement|SmartPointerEx"""),
        BlockingClass.PSI_RESOLVE to Regex(
            """\.resolve\(|ResolveCache|JavaPsiFacade|PsiResolveHelper|multiResolve|PsiManager\.findFile|""" +
                """PsiFileImpl\.(calcTreeElement|getStubTree)|loadTreeElement"""
        ),
        BlockingClass.LOCK_WAIT to Regex(
            """ReentrantReadWriteLock|ReadMostlyRWLock|NestedLocksThreadingSupport|ThreadingSupport.*""" +
                """(getWritePermit|runWriteAction|acquireWrite)|waitForReaders|RWLock|getReadPermit|""" +
                """acquireReadPermit|upgradeWritePermit|locking\.impl\.RunSuspend|core\.rwmutex"""
        ),
        BlockingClass.UI_INIT to Regex("""Configurable\.createComponent|createToolWindowContent|createCenterPanel|ConfigurableWrapper"""),
        BlockingClass.PSI_VISIT to Regex(
            """Visitor\w*\.visit|\.accept\(|acceptChildren|PsiRecursiveElement|processElements|""" +
                """PsiTreeUtil\.(find|collect|process)"""
        ),
    )

    /** Generic JDK waiting frames: they don't say what is awaited, so they are skipped to find the real call. */
    private val TRANSPARENT = Regex(
        """^(jdk\.internal\.misc\.Unsafe|java\.util\.concurrent\.locks\.|java\.lang\.Object\.wait|""" +
            """java\.lang\.Thread\.(sleep|onSpinWait)|java\.util\.concurrent\.(ForkJoin|ThreadPool))"""
    )

    /** How many frames from the top are considered. */
    private const val TOP_FRAMES = 15

    /** The EDT waiting for the next event: the dump caught it idle (a modal loop, or the freeze already ended). */
    private val IDLE = Regex("""^(java\.awt\.EventQueue|com\.intellij\.ide\.IdeEventQueue)\.getNextEvent$""")

    /** A thread inside a read action that blocks writers (non-cancellable), or inside a background write action. */
    private val HOLDS_LOCK = Regex(
        """\.(runReadAction|tryRunReadAction|insideReadAction|runWriteAction)$|ReadAction\.(compute|run)$|""" +
            """ThreadingSupport\.runWriteIntentReadAction$"""
    )

    /** A thread still trying to get the lock, so not the one holding it. */
    private val WAITING_FOR_LOCK = Regex(
        """getReadPermit|acquireReadPermit|getWritePermit|upgradeWritePermit|ReadMostlyRWLock\.(waitABit|writeLock)|""" +
            """locking\.impl\.RunSuspend|core\.rwmutex"""
    )

    /** Frames that never explain a freeze by themselves: JDK, Kotlin, collections and the locking machinery. */
    private val NOT_WHERE = Regex(
        """^(java\.|javax\.|jdk\.|sun\.|com\.sun\.|kotlin\.|kotlinx\.|it\.unimi\.dsi\.fastutil\.|com\.google\.common\.|""" +
            """com\.intellij\.platform\.locking\.|com\.intellij\.core\.rwmutex\.|com\.intellij\.openapi\.application\.impl\.|""" +
            """com\.intellij\.openapi\.progress\.|com\.intellij\.concurrency\.|com\.intellij\.util\.concurrency\.)"""
    )

    /** The blocking class from the top frames of a stack, given as `class.method` strings. */
    fun classifyFrames(methods: List<String>): BlockingClass {
        for (method in methods.take(TOP_FRAMES)) {
            if (TRANSPARENT.containsMatchIn(method)) continue
            LEAVES.firstOrNull { (_, pattern) -> pattern.containsMatchIn(method) }?.let { return it.first }
        }
        return if (methods.isEmpty()) BlockingClass.OTHER else BlockingClass.CPU
    }

    fun analyze(threads: List<DumpThread>): DumpAnalysis {
        val edt = threads.firstOrNull { it.isEdt }
        if (edt == null || edt.frames.isEmpty()) {
            return DumpAnalysis(BlockingClass.OTHER, edt, null, null, null)
        }
        val methods = edt.frames.map { it.method }
        val firstReal = methods.firstOrNull { !TRANSPARENT.containsMatchIn(it) }
        if (firstReal != null && IDLE.matches(firstReal)) {
            return DumpAnalysis(BlockingClass.IDLE, edt, null, null, null)
        }
        val blocking = classifyFrames(methods)
        val holder = if (blocking == BlockingClass.LOCK_WAIT) lockHolder(threads) else null
        val holderBlocking = holder?.let { classifyFrames(it.frames.map { f -> f.method }) }
        val where = whereIn(holder ?: edt)
        return DumpAnalysis(blocking, edt, holder, holderBlocking, where)
    }

    /** The thread most likely holding the lock: inside a read or write action, not waiting to get it, running first. */
    fun lockHolder(threads: List<DumpThread>): DumpThread? =
        threads.asSequence()
            .filter { !it.isEdt && it.frames.any { f -> HOLDS_LOCK.containsMatchIn(f.method) } }
            .filter { thread ->
                thread.frames.asSequence()
                    .filter { !TRANSPARENT.containsMatchIn(it.method) }
                    .take(TOP_FRAMES)
                    .none { WAITING_FOR_LOCK.containsMatchIn(it.method) }
            }
            .sortedBy { if (it.state == "RUNNABLE") 0 else 1 }
            .firstOrNull()

    fun whereIn(thread: DumpThread): DumpFrame? = thread.frames.firstOrNull { !NOT_WHERE.containsMatchIn(it.method) }
}
