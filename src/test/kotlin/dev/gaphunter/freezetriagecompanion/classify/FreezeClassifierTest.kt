package dev.gaphunter.freezetriagecompanion.classify

import dev.gaphunter.freezetriagecompanion.Dumps
import dev.gaphunter.freezetriagecompanion.dump.ThreadDumpParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreezeClassifierTest {

    @Test
    fun `classifier matches the expected class on every real YouTrack stack`() {
        val cases = javaClass.getResourceAsStream("/youtrack_stacks.tsv")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
        assertTrue("expected the full set of real stacks, got ${cases.size}", cases.size >= 390)
        val mismatches = cases.mapNotNull { (ticket, expected, frames) ->
            val actual = FreezeClassifier.classifyFrames(frames.split(' ')).id
            if (actual == expected) null else "$ticket: expected $expected, got $actual"
        }
        assertEquals("mismatches:\n" + mismatches.joinToString("\n"), 0, mismatches.size)
    }

    @Test
    fun `transparent JDK frames are skipped to find the real call`() {
        val frames = listOf(
            "jdk.internal.misc.Unsafe.park",
            "java.util.concurrent.locks.LockSupport.park",
            "java.util.concurrent.CompletableFuture.get",
            "com.foo.Bar.compute",
        )
        assertEquals(BlockingClass.WAIT_OTHER_THREAD, FreezeClassifier.classifyFrames(frames))
        assertEquals(BlockingClass.CPU, FreezeClassifier.classifyFrames(listOf("com.foo.Bar.loop", "com.foo.Bar.compute")))
        assertEquals(BlockingClass.OTHER, FreezeClassifier.classifyFrames(emptyList()))
    }

    @Test
    fun `EDT doing file IO is classified as IO, and where is the first frame outside the JDK`() {
        val analysis = FreezeClassifier.analyze(ThreadDumpParser.parse(Dumps.EDT_IO))
        assertEquals(BlockingClass.IO, analysis.blocking)
        assertEquals("com.acme.slowplugin.ConfigLoader.load", analysis.where!!.method)
        assertNull(analysis.lockHolder)
    }

    @Test
    fun `lock wait names the thread holding the read lock, not the one waiting for it`() {
        val analysis = FreezeClassifier.analyze(ThreadDumpParser.parse(Dumps.LOCK_WAIT))
        assertEquals(BlockingClass.LOCK_WAIT, analysis.blocking)
        assertEquals("ApplicationImpl pooled thread 3", analysis.lockHolder!!.name)
        assertEquals(BlockingClass.PROCESS, analysis.holderBlocking)
        assertEquals("com.intellij.execution.process.CapturingProcessHandler.runProcess", analysis.where!!.method)
    }

    @Test
    fun `EDT waiting for the next event is idle, not computation`() {
        val analysis = FreezeClassifier.analyze(ThreadDumpParser.parse(Dumps.IDLE))
        assertEquals(BlockingClass.IDLE, analysis.blocking)
        assertNull(analysis.where)
    }

    @Test
    fun `dump without an EDT gives no classification`() {
        val analysis = FreezeClassifier.analyze(ThreadDumpParser.parse(Dumps.dump(Dumps.thread("worker", "RUNNABLE", "com.foo.A.b(A.kt:1)"))))
        assertEquals(BlockingClass.OTHER, analysis.blocking)
        assertNull(analysis.edt)
    }
}
