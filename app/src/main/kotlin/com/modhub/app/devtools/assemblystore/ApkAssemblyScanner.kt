package com.modhub.app.devtools.assemblystore

import java.io.File

/** How an assembly was packaged in the APK. */
enum class AssemblyPackaging(val label: String) {
    /** `assemblies/Foo.dll` (or `assemblies/<abi>/Foo.dll`), Xamarin / .NET 6-8 without a store. */
    CLASSIC_DLL("assemblies/*.dll"),

    /** `assemblies/assemblies[.<abi>].blob` + `assemblies.manifest` (store v1/v2). */
    ASSEMBLY_STORE("assembly store (.blob)"),

    /** .NET 9+: `lib/<abi>/libassemblies.<abi>.blob.so`, store in the ELF `payload` section. */
    ELF_ASSEMBLY_STORE("ELF-wrapped assembly store (libassemblies.*.blob.so)"),

    /** .NET 9+ without a store: `lib/<abi>/lib_Foo.dll.so` (best effort: same `payload` wrapper). */
    ELF_WRAPPED_DLL("ELF-wrapped assembly (lib_*.dll.so)"),
}

/** One extracted (and LZ4-decompressed) assembly image. */
class ExtractedAssembly(
    /** File name, e.g. `StardewValley.dll`. */
    val fileName: String,
    val bytes: ByteArray,
    /** Entry inside the APK the image came from (the store or the .dll). */
    val entry: String,
    val packaging: AssemblyPackaging,
    val wasCompressed: Boolean,
)

class ApkScanResult(
    /** Wanted assemblies found, keyed by lower-case file name. */
    val extracted: Map<String, ExtractedAssembly>,
    /** Every assembly name seen (without `.dll`), sorted case-insensitively. */
    val assemblyNames: List<String>,
    val packagings: Set<AssemblyPackaging>,
    val messages: List<String>,
) {
    fun describe(): String = buildString {
        if (packagings.isEmpty()) append("no .NET assemblies") else {
            append(packagings.joinToString(" + ") { it.label })
            append(", ${assemblyNames.size} assemblies")
        }
    }
}

/**
 * Finds .NET assemblies in one APK, in all the layouts .NET for Android has used:
 * classic `assemblies/<Name>.dll`, assembly store blobs (+ manifest), .NET 9+ ELF-wrapped stores and
 * ELF-wrapped single assemblies. Only the [wanted] images are extracted (decompressing `XALZ`);
 * nameless store entries are extracted once to read their name from the PE metadata.
 */
class ApkAssemblyScanner(private val logger: (String) -> Unit = {}) {

    fun scan(apk: File, wanted: Collection<String>): ApkScanResult =
        ZipArchiveSource(apk).use { scan(it, wanted) }

    fun scan(archive: ArchiveSource, wanted: Collection<String>): ApkScanResult {
        val wantedKeys = wanted.map { key(it) }.toSet()
        val extracted = LinkedHashMap<String, ExtractedAssembly>()
        val names = sortedSetOf(String.CASE_INSENSITIVE_ORDER)
        val packagings = LinkedHashSet<AssemblyPackaging>()
        val messages = ArrayList<String>()

        fun note(msg: String) {
            messages += msg
            logger(msg)
        }

        fun offer(name: String, packaging: AssemblyPackaging, entry: String, image: () -> ByteArray) {
            names += name
            packagings += packaging
            val k = key(name)
            if (k !in wantedKeys || k in extracted) return
            try {
                val raw = image()
                val bytes = Xalz.decompressIfNeeded(raw)
                extracted[k] = ExtractedAssembly("$name.dll", bytes, entry, packaging, wasCompressed = raw !== bytes)
            } catch (e: RuntimeException) {
                note("Couldn't extract $name from $entry: ${e.message}")
            }
        }

        // (a) classic assemblies/*.dll and assemblies/<abi>/*.dll, arm64 first.
        archive.entryNames
            .mapNotNull { e -> CLASSIC.matchEntire(e)?.let { e to it } }
            .sortedBy { (_, m) -> AssemblyStoreExplorer.abiPreference(m.groupValues[1].ifEmpty { "arm64-v8a" }) }
            .forEach { (e, m) -> offer(m.groupValues[2], AssemblyPackaging.CLASSIC_DLL, e) { archive.read(e) } }

        // (b)+(c) assembly stores. Every store set under assemblies/ (normally just "assemblies",
        // "*_assemblies" in some layouts); the explorer falls back to the ELF stores in lib/.
        val baseNames = archive.entryNames
            .mapNotNull { STORE_FILE.matchEntire(it)?.groupValues?.get(1) }
            .distinct()
            .sortedBy { if (it == "assemblies") 0 else 1 }
            .ifEmpty { listOf("assemblies") }
        for (baseName in baseNames) {
            val explorer = AssemblyStoreExplorer.fromArchive(archive, "assemblies/$baseName.") { level, msg ->
                if (level >= AssemblyStoreLogLevel.WARNING) note(msg)
            }
            if (explorer.assemblies.isEmpty()) continue
            val packaging = if (explorer.usedElfFormat) AssemblyPackaging.ELF_ASSEMBLY_STORE else AssemblyPackaging.ASSEMBLY_STORE
            val entryOf = { a: AssemblyStoreAssembly ->
                explorer.storeEntries.firstOrNull { e -> a.store.arch.isNotEmpty() && e.contains(a.store.arch) }
                    ?: explorer.storeEntries.firstOrNull().orEmpty()
            }
            // assembliesByName first (arm64 preferred, deduplicated), then any unnamed leftovers.
            val ordered = explorer.assembliesByName.values + explorer.assemblies
            val seen = HashSet<AssemblyStoreAssembly>()
            for (a in ordered) {
                if (!seen.add(a)) continue
                var cachedRaw: ByteArray? = null
                val name = a.name.ifEmpty {
                    // No manifest / name table: read the name from the image itself (C# ExtractImage does the same).
                    val image = try {
                        Xalz.decompressIfNeeded(a.rawImage().also { cachedRaw = it })
                    } catch (e: RuntimeException) {
                        note("Couldn't read unnamed store entry ${a.dllName}: ${e.message}"); null
                    }
                    image?.let { DotNetAssemblyName.derive(it) } ?: a.dllName.removeSuffix(".dll")
                }
                val raw = cachedRaw
                offer(name, packaging, entryOf(a)) { raw ?: a.rawImage() }
            }
        }

        // (e) ELF-wrapped single assemblies (lib/<abi>/lib_Foo.dll.so).
        archive.entryNames
            .mapNotNull { e -> ELF_DLL.matchEntire(e)?.let { e to it } }
            .sortedBy { (_, m) -> AssemblyStoreExplorer.abiPreference(m.groupValues[1]) }
            .forEach { (e, m) ->
                offer(m.groupValues[2], AssemblyPackaging.ELF_WRAPPED_DLL, e) {
                    val elf = archive.read(e)
                    val s = ElfPayload.findPayloadSection(elf)
                        ?: throw AssemblyStoreFormatException("no '${ElfPayload.SECTION_NAME}' section")
                    elf.copyOfRange(s.offset, s.offset + s.size)
                }
            }

        return ApkScanResult(extracted, names.toList(), packagings, messages)
    }

    private fun key(name: String) = name.lowercase().removeSuffix(".dll")

    companion object {
        private val CLASSIC = Regex("assemblies/(?:([^/]+)/)?([^/]+)\\.dll")
        private val STORE_FILE = Regex("assemblies/([^/.]*assemblies)\\.(?:[^/]+\\.)?(?:blob|manifest)")
        private val ELF_DLL = Regex("lib/([^/]+)/lib_([^/]+)\\.dll\\.so")
    }
}
