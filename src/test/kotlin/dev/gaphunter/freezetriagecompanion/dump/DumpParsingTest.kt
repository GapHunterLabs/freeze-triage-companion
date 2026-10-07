package dev.gaphunter.freezetriagecompanion.dump

import dev.gaphunter.freezetriagecompanion.Dumps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import java.time.LocalDateTime

class DumpParsingTest {

    @Test
    fun `finished freeze folder gives start, build, top frame and duration`() {
        val folder = FreezeFolder.parse(Path.of("log", "threadDumps-freeze-20260920-170950-IU-262.8665.337-Unsafe.park-7sec"))!!
        assertEquals(LocalDateTime.of(2026, 9, 20, 17, 9, 50), folder.started)
        assertEquals("IU-262.8665.337", folder.productBuild)
        assertEquals("Unsafe.park", folder.topFrame)
        assertEquals(7, folder.durationSeconds)
    }

    @Test
    fun `unfinished freeze folder has no top frame and no duration`() {
        val folder = FreezeFolder.parse(Path.of("threadDumps-freeze-20260805-155920-IU-252.28539.54"))!!
        assertEquals("252.28539.54", folder.build)
        assertNull(folder.topFrame)
        assertNull(folder.durationSeconds)
    }

    @Test
    fun `other products, snapshot builds and dashes in the frame`() {
        assertEquals("PY", FreezeFolder.parse(Path.of("threadDumps-freeze-20260101-000000-PY-261.1.2-Foo.bar-12sec"))!!.product)
        assertEquals("262.SNAPSHOT", FreezeFolder.parse(Path.of("threadDumps-freeze-20260101-000000-IC-262.SNAPSHOT"))!!.build)
        val dashed = FreezeFolder.parse(Path.of("threadDumps-freeze-20260101-000000-IU-262.1-Foo-bar.run-3sec"))!!
        assertEquals("Foo-bar.run", dashed.topFrame)
        assertEquals(3, dashed.durationSeconds)
    }

    @Test
    fun `other folders are not freezes`() {
        assertNull(FreezeFolder.parse(Path.of("threadDumps-20260101-000000-IU-262.1")))
        assertNull(FreezeFolder.parse(Path.of("idea.log")))
        assertNull(FreezeFolder.parse(Path.of("threadDumps-freeze-2026-IU-262.1")))
    }

    @Test
    fun `parser strips module prefixes, keeps lambdas and stops at the coroutine dump`() {
        val threads = ThreadDumpParser.parse(Dumps.EDT_IO)
        assertEquals(listOf("AWT-EventQueue-0", "Common-Cleaner"), threads.map { it.name })
        val edt = threads.first()
        assertTrue(edt.isEdt)
        assertEquals("RUNNABLE", edt.state)
        assertEquals("sun.nio.fs.WindowsNativeDispatcher.CreateFile0", edt.frames[0].method)
        assertEquals("Native Method", edt.frames[0].location)
        assertEquals("ConfigLoader.load", edt.frames[3].shortName)
        assertEquals("java.awt.EventDispatchThread.run", edt.frames.last().method)

        val lambda = ThreadDumpParser.parse(
            Dumps.dump(Dumps.thread("t", "RUNNABLE", "app//com.foo.Bar\$\$Lambda/0x000000001a447200.run(Unknown Source)"))
        )
        assertEquals("com.foo.Bar\$\$Lambda/0x000000001a447200.run", lambda.single().frames.single().method)
        assertEquals("Bar.run", lambda.single().frames.single().shortName)
    }

    @Test
    fun `plain jstack output is read too`() {
        val jstack = """
            Full thread dump OpenJDK 64-Bit Server VM (21.0.5+8 mixed mode):

            "AWT-EventQueue-0" #31 [12345] prio=6 os_prio=0 cpu=10.00ms elapsed=5.00s tid=0x1 nid=12345 runnable  [0x2]
               java.lang.Thread.State: RUNNABLE
            	at java.base/java.io.FileInputStream.readBytes(Native Method)
            	at com.foo.Reader.read(Reader.java:10)
            	- locked <0x00000007> (a java.lang.Object)
        """.trimIndent()
        val edt = ThreadDumpParser.parse(jstack).single()
        assertEquals("AWT-EventQueue-0", edt.name)
        assertEquals(listOf("java.io.FileInputStream.readBytes", "com.foo.Reader.read"), edt.frames.map { it.method })
    }
}
