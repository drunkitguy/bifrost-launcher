package dev.bifrost.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory

/**
 * Game systems and their emulator launch commands, in ES-DE's Android format
 * (bundled from ES-DE, MIT). A bifrost_systems.xml / bifrost_find_rules.xml in the ROMs folder
 * adds or replaces entries, which is how custom launchers are defined.
 */
internal object Systems {
    class Command(val label: String, val raw: String)
    class System(val name: String, val fullname: String, val extensions: Set<String>, val commands: List<Command>)

    @Volatile var byName: Map<String, System> = emptyMap()
        private set
    /** Emulator name (as in %EMULATOR_NAME%) to "package/activity" candidates. */
    @Volatile private var emulators: Map<String, List<String>> = emptyMap()

    @Synchronized
    fun load(ctx: Context, customSystems: InputStream? = null, customRules: InputStream? = null) {
        val systems = ctx.assets.open("es_systems.xml").use(::parseSystems).associateBy { it.name } +
            (customSystems?.use(::parseSystems)?.associateBy { it.name } ?: emptyMap())
        val rules = ctx.assets.open("es_find_rules.xml").use(::parseFindRules) +
            (customRules?.use(::parseFindRules) ?: emptyMap())
        emulators = rules
        byName = systems
    }

    @Synchronized
    fun ensureLoaded(ctx: Context) {
        if (byName.isEmpty()) load(ctx)
    }

    /** A ROM folder matches a system by ES-DE folder name ("gc") or full name ("Nintendo GameCube"). */
    fun forFolder(folder: String): System? {
        val f = folder.lowercase()
        return byName[f] ?: byName.values.firstOrNull { it.fullname.lowercase() == f }
    }

    /** The installed app behind %EMULATOR_NAME%, or null. A name with a '/' is taken as a component. */
    fun emulator(pm: PackageManager, name: String): ComponentName? =
        (emulators[name] ?: listOf(name).filter { '/' in it }).firstNotNullOfOrNull { entry ->
            val pkg = entry.substringBefore('/')
            val activity = entry.substringAfter('/').let { if (it.startsWith('.')) pkg + it else it }
            runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()?.let { ComponentName(pkg, activity) }
        }
}

internal fun parseSystems(input: InputStream): List<Systems.System> {
    val out = mutableListOf<Systems.System>()
    SAXParserFactory.newInstance().newSAXParser().parse(input, object : DefaultHandler() {
        val text = StringBuilder()
        var name = ""
        var fullname = ""
        var extensions = emptySet<String>()
        var label = ""
        val commands = mutableListOf<Systems.Command>()

        override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
            text.setLength(0)
            when (qName) {
                "system" -> {
                    name = ""; fullname = ""; extensions = emptySet(); commands.clear()
                }
                "command" -> label = attrs.getValue("label") ?: ""
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            val value = text.toString().trim()
            when (qName) {
                "name" -> name = value
                "fullname" -> fullname = value
                "extension" -> extensions = value.split(Regex("\\s+")).map { it.lowercase().removePrefix(".") }.toSet()
                "command" -> commands += Systems.Command(label.ifEmpty { value.substringBefore(' ') }, value)
                "system" -> if (name.isNotEmpty()) out += Systems.System(name, fullname.ifEmpty { name }, extensions, commands.toList())
            }
        }
    })
    return out
}

internal fun parseFindRules(input: InputStream): Map<String, List<String>> {
    val out = LinkedHashMap<String, MutableList<String>>()
    SAXParserFactory.newInstance().newSAXParser().parse(input, object : DefaultHandler() {
        val text = StringBuilder()
        var emulator = ""
        var android = false

        override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
            text.setLength(0)
            when (qName) {
                "emulator" -> emulator = attrs.getValue("name") ?: ""
                "rule" -> android = attrs.getValue("type") == "androidpackage"
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            if (qName == "entry" && android) out.getOrPut(emulator) { mutableListOf() } += text.toString().trim()
        }
    })
    return out
}
