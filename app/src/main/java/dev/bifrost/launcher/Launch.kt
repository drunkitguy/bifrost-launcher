package dev.bifrost.launcher

/**
 * Turns an ES-DE Android launch command into an intent description. Pure, so it is unit-tested;
 * [Library.launch] turns the plan into a real Intent.
 */
internal object Launch {
    class Plan(
        val emulator: String,
        val action: String?,
        val categories: List<String>,
        val data: String?,
        val mimeType: String?,
        val extras: List<Pair<String, Any>>,
        val clearTask: Boolean,
        val clearTop: Boolean,
        /** The command hands over the ROM as a content URI: grant the emulator read access. */
        val grantsUri: Boolean,
    )

    /** What a command's variables expand to for one game. [inject] reads the ROM file's text. */
    class Vars(
        val path: String,
        val uri: String,
        val baseName: String,
        val romRoot: String,
        val pkg: (String) -> String?,
        val inject: () -> String?,
    )

    /** `%KEY%=value` pairs; a value is either "quoted" or runs to the next space. */
    fun tokens(command: String): List<Pair<String, String?>> {
        val out = mutableListOf<Pair<String, String?>>()
        var i = 0
        while (i < command.length) {
            if (command[i].isWhitespace()) { i++; continue }
            val start = i
            while (i < command.length && !command[i].isWhitespace() && !(command[i] == '=' && i > start && command[i - 1] == '%')) i++
            val key = command.substring(start, i)
            if (i < command.length && command[i] == '=') {
                i++
                val value = if (i < command.length && command[i] == '"') {
                    val end = command.indexOf('"', i + 1).let { if (it < 0) command.length else it }
                    command.substring(i + 1, end).also { i = end + 1 }
                } else {
                    val end = (i until command.length).firstOrNull { command[it].isWhitespace() } ?: command.length
                    command.substring(i, end).also { i = end }
                }
                out += key to value
            } else {
                out += key to null
            }
        }
        return out
    }

    /** Null when the command uses something Bifrost can't do or its emulator isn't installed. */
    fun plan(command: String, v: Vars): Plan? {
        var emulator: String? = null
        var pkg = ""
        var action: String? = null
        val categories = mutableListOf<String>()
        var data: String? = null
        var mime: String? = null
        val extras = mutableListOf<Pair<String, Any>>()
        var clearTask = false
        var clearTop = false
        var grants = false

        fun expand(raw: String): String? {
            var s = raw
            if ("%INJECT%=" in s) {
                // shortcut: injects the ROM's own text, which is what every bundled command points at
                // (Vita title IDs, GameNative app ids); upgrade if a command injects another file.
                s = s.substringBefore("%INJECT%=") + (v.inject() ?: return null).trim()
            }
            if ("%ROMSAF%" in s || "%ROMPROVIDER%" in s) grants = true
            val gameDir = v.path.substringBeforeLast('/')
            return s.replace("%QUOTATION%", "\"")
                .replace("%ROMSAF%", v.uri).replace("%ROMPROVIDER%", v.uri)
                .replace("%ROMRAW%", v.path).replace("%ROM%", v.path)
                .replace("%GAMEDIRRAW%", gameDir).replace("%ROMPATHRAW%", v.romRoot).replace("%ROMPATH%", v.romRoot)
                .replace("%BASENAME%", v.baseName)
                .replace("%EXTERNALDATA%", "/storage/emulated/0").replace("%INTERNALDATA%", "/data/data")
                .replace("%ANDROIDPACKAGE%", pkg)
                .replace("\\/", "/")
        }

        for ((key, raw) in tokens(command)) {
            val value = raw?.let { expand(it) ?: return null }
            when {
                key.startsWith("%EMULATOR_") -> {
                    val name = key.removePrefix("%EMULATOR_").removeSuffix("%")
                    pkg = v.pkg(name) ?: return null
                    emulator = name
                }
                key == "%ACTION%" -> action = value
                key == "%CATEGORY%" -> value?.let { categories += it }
                key == "%DATA%" -> data = value
                key == "%MIMETYPE%" -> mime = value
                key == "%ACTIVITY_CLEAR_TASK%" -> clearTask = true
                key == "%ACTIVITY_CLEAR_TOP%" -> clearTop = true
                key.startsWith("%EXTRA") && value != null -> {
                    val type = key.substringBefore('_')
                    val name = key.substringAfter('_').removeSuffix("%")
                    extras += name to when (type) {
                        "%EXTRABOOL" -> value.toBoolean()
                        "%EXTRAINTEGER" -> value.toIntOrNull() ?: return null
                        "%EXTRAARRAY" -> value.split(',').toTypedArray()
                        "%EXTRA" -> value
                        else -> return null
                    }
                }
                else -> return null // %ANDROIDAPP% and friends: not supported
            }
        }
        return Plan(emulator ?: return null, action, categories, data, mime, extras, clearTask, clearTop, grants)
    }
}
