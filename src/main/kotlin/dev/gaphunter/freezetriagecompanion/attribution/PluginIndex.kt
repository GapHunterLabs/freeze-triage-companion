package dev.gaphunter.freezetriagecompanion.attribution

import dev.gaphunter.freezetriagecompanion.dump.DumpFrame
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/** A plugin installed by the user (not bundled with the IDE), as declared in its `plugin.xml`. */
data class PluginOwner(val id: String, val name: String, val version: String?, val vendor: String?) {
    override fun toString(): String = buildString {
        append(name)
        version?.let { append(' ').append(it) }
        vendor?.let { append(" by ").append(it) }
    }
}

/**
 * Maps Java packages to the user-installed plugin that ships them, by reading the jars in the plugins directory.
 *
 * Only the packages a caller asks about are kept, so the index stays small. Reads jar directories only, never class
 * bytes, and loads nothing. A frame whose package is not found belongs to the IDE (platform or bundled plugin).
 */
class PluginIndex private constructor(private val owners: Map<String, PluginOwner>) {

    fun ownerOf(frame: DumpFrame): PluginOwner? = owners[packageOf(frame.method)]

    /** The first frame from the top that belongs to a user-installed plugin, with that plugin. */
    fun firstPluginFrame(frames: List<DumpFrame>): Pair<DumpFrame, PluginOwner>? {
        for (frame in frames) {
            val owner = ownerOf(frame) ?: continue
            return frame to owner
        }
        return null
    }

    val size: Int get() = owners.size

    companion object {
        val EMPTY = PluginIndex(emptyMap())

        /** `com.foo` for `com.foo.Bar$Inner.method` (and for `com.foo.Bar$$Lambda/0x01.run`). */
        fun packageOf(method: String): String = method.substringBeforeLast('.', "").substringBeforeLast('.', "")

        private val ID = Regex("""<id>\s*([^<]+?)\s*</id>""")
        private val NAME = Regex("""<name>\s*([^<]+?)\s*</name>""")
        private val VERSION = Regex("""<version>\s*([^<]+?)\s*</version>""")
        private val VENDOR = Regex("""<vendor[^>]*>\s*([^<]+?)\s*</vendor>""")

        /**
         * Scans each plugin under [pluginsDir] (a folder with `lib/` jars, or a single jar) and keeps the owners of
         * the packages in [packages]. [checkCanceled] is called between jars.
         */
        fun build(pluginsDir: Path?, packages: Set<String>, checkCanceled: () -> Unit = {}): PluginIndex {
            if (pluginsDir == null || packages.isEmpty() || !pluginsDir.isDirectory()) return EMPTY
            val owners = HashMap<String, PluginOwner>()
            val entries = runCatching { Files.list(pluginsDir).use { it.sorted().toList() } }.getOrDefault(emptyList())
            for (entry in entries) {
                checkCanceled()
                val jars = when {
                    entry.isDirectory() -> jarsUnder(entry.resolve("lib"))
                    entry.isRegularFile() && entry.extension == "jar" -> listOf(entry)
                    else -> emptyList()
                }
                if (jars.isEmpty()) continue
                var owner: PluginOwner? = null
                val found = HashSet<String>()
                for (jar in jars) {
                    checkCanceled()
                    runCatching {
                        ZipFile(jar.toFile()).use { zip ->
                            if (owner == null) {
                                zip.getEntry("META-INF/plugin.xml")?.let { xml ->
                                    owner = ownerFrom(zip.getInputStream(xml).use { it.readBytes() }.decodeToString())
                                }
                            }
                            for (zipEntry in zip.entries()) {
                                val path = zipEntry.name
                                if (!path.endsWith(".class")) continue
                                val pkg = path.substringBeforeLast('/', "").replace('/', '.')
                                if (pkg in packages) found += pkg
                            }
                        }
                    }
                }
                val resolved = owner ?: continue
                for (pkg in found) owners.putIfAbsent(pkg, resolved)
            }
            return PluginIndex(owners)
        }

        private fun jarsUnder(lib: Path): List<Path> =
            if (!lib.isDirectory()) emptyList()
            else runCatching {
                Files.walk(lib, 3).use { paths -> paths.filter { it.isRegularFile() && it.extension == "jar" }.sorted().toList() }
            }.getOrDefault(emptyList())

        internal fun ownerFrom(pluginXml: String): PluginOwner? {
            val name = NAME.find(pluginXml)?.groupValues?.get(1)
            val id = ID.find(pluginXml)?.groupValues?.get(1) ?: name ?: return null
            return PluginOwner(
                id = id,
                name = name ?: id,
                version = VERSION.find(pluginXml)?.groupValues?.get(1),
                vendor = VENDOR.find(pluginXml)?.groupValues?.get(1),
            )
        }
    }
}
