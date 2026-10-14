package com.modhub.app.devtools.assemblystore

import java.util.TreeMap

enum class AssemblyStoreLogLevel { DEBUG, INFO, WARNING, ERROR }

/**
 * Port of `AssemblyStoreExplorer` (launcher/AssemblyStore), archive mode only (ModHub reads
 * installed APKs; the C# filesystem/aab path-guessing constructors are not needed).
 *
 * Reads every `<basePathInArchive>*.blob` as a store and `*.manifest` as the manifest. If no
 * blob matched, falls back to the .NET 9+ layout: `lib/<abi>/libassemblies.<abi>.blob.so` ELF
 * wrappers whose `payload` section is the store. Then cross-links the stores through the global
 * index of store 0 and the manifest (names for v1; v2 names come from the store's name table).
 *
 * Differences from the C#: a store that fails to parse is logged as an error and skipped
 * instead of aborting the whole set; ELF stores are read with arm64-v8a first so that, for
 * duplicate names, the arm64 copy wins in [assembliesByName].
 */
class AssemblyStoreExplorer private constructor(
    private val customLogger: ((AssemblyStoreLogLevel, String) -> Unit)?,
) {
    private var indexStore: AssemblyStoreReader? = null
    private var manifest: AssemblyStoreManifestReader? = null

    val assembliesByName = TreeMap<String, AssemblyStoreAssembly>(String.CASE_INSENSITIVE_ORDER)
    val assembliesByHash32 = HashMap<UInt, AssemblyStoreAssembly>()
    val assembliesByHash64 = HashMap<ULong, AssemblyStoreAssembly>()
    val assemblies = ArrayList<AssemblyStoreAssembly>()
    val stores = TreeMap<UInt, MutableList<AssemblyStoreReader>>()

    /** Archive entries the stores were read from, e.g. `lib/arm64-v8a/libassemblies.arm64-v8a.blob.so`. */
    val storeEntries = ArrayList<String>()
    var usedElfFormat = false; private set
    var hasErrors = false; private set
    var hasWarnings = false; private set
    val messages = ArrayList<String>()

    val isCompleteSet: Boolean get() = indexStore != null && manifest != null
    val numberOfStores: Int get() = stores.values.sumOf { it.size }

    private fun log(level: AssemblyStoreLogLevel, message: String) {
        when (level) {
            AssemblyStoreLogLevel.ERROR -> hasErrors = true
            AssemblyStoreLogLevel.WARNING -> hasWarnings = true
            else -> Unit
        }
        messages += "$level: $message"
        customLogger?.invoke(level, message)
    }

    private fun processStores() {
        val index = indexStore
        if (stores.isEmpty() || index == null) return

        processIndex(index.globalIndex32, "32") { he, assembly ->
            assembly.hash32 = he.hash.toUInt()
            assembly.runtimeIndex = he.mappingIndex
            manifest?.entriesByHash32?.get(assembly.hash32)?.let { assembly.name = it.name }
            assembliesByHash32.putIfAbsent(assembly.hash32, assembly)
        }

        processIndex(index.globalIndex64, "64") { he, assembly ->
            assembly.hash64 = he.hash
            if (assembly.runtimeIndex != he.mappingIndex) {
                log(
                    AssemblyStoreLogLevel.DEBUG, // WARNING in the C#; always true for v2 (no 32-bit index), so demoted
                    "assembly with hashes 0x${assembly.hash32.toString(16)} and 0x${assembly.hash64.toString(16)} has a different " +
                        "32-bit runtime index (${assembly.runtimeIndex}) than the 64-bit runtime index(${he.mappingIndex})",
                )
            }
            val me = manifest?.entriesByHash64?.get(assembly.hash64)
            if (me != null) {
                if (assembly.name.isEmpty()) {
                    log(AssemblyStoreLogLevel.WARNING, "32-bit hash 0x${assembly.hash32.toString(16)} did not match any assembly name in the manifest")
                    assembly.name = me.name
                    if (assembly.name.isEmpty()) {
                        log(AssemblyStoreLogLevel.WARNING, "64-bit hash 0x${assembly.hash64.toString(16)} did not match any assembly name in the manifest")
                    }
                } else if (assembly.name != me.name) {
                    log(
                        AssemblyStoreLogLevel.WARNING,
                        "32-bit hash 0x${assembly.hash32.toString(16)} maps to assembly name '${assembly.name}', however 64-bit hash " +
                            "0x${assembly.hash64.toString(16)} for the same entry matches assembly name '${me.name}'",
                    )
                }
            }
            assembliesByHash64.putIfAbsent(assembly.hash64, assembly)
        }

        for ((_, list) in stores) {
            if (list.size < 2) continue
            val template = list[0]
            for (other in list.drop(1)) {
                if (!template.hasIdenticalContent(other)) {
                    log(AssemblyStoreLogLevel.ERROR, "Store ID ${template.storeId} for architecture ${other.arch} is not identical to other stores with the same ID")
                }
            }
        }
    }

    private inline fun processIndex(
        index: List<AssemblyStoreHashEntry>,
        bitness: String,
        handler: (AssemblyStoreHashEntry, AssemblyStoreAssembly) -> Unit,
    ) {
        for (he in index) {
            val storeList = stores[he.storeId]
            if (storeList == null) {
                log(AssemblyStoreLogLevel.WARNING, "store with id ${he.storeId} not part of the set")
                continue
            }
            for (store in storeList) {
                if (he.localStoreIndex >= store.assemblies.size.toUInt()) {
                    log(
                        AssemblyStoreLogLevel.WARNING,
                        "$bitness-bit index entry with hash 0x${he.hash.toString(16)} has invalid store ${store.storeId} index " +
                            "${he.localStoreIndex} (maximum allowed is ${store.assemblies.size})",
                    )
                    continue
                }
                val assembly = store.assemblies[he.localStoreIndex.toInt()]
                handler(he, assembly)
                assembliesByName.putIfAbsent(assembly.name, assembly)
            }
        }
    }

    private fun readStoreSetFromArchive(archive: ArchiveSource, basePathInArchive: String) {
        var foundBlob = false
        for (name in archive.entryNames) {
            if (!name.startsWith(basePathInArchive)) continue
            if (name.endsWith(".blob")) {
                foundBlob = true
                addStore(name) { AssemblyStoreReader(archive.read(name), getStoreArch(name)) }
            } else if (name.endsWith(".manifest")) {
                manifest = AssemblyStoreManifestReader(archive.read(name))
            }
        }
        // .NET 9+ stores assemblies as ELF-wrapped .so files in lib/
        if (!foundBlob) readStoreSetFromArchiveElfFormat(archive)
    }

    /** .NET 9+: blobs are ELF-wrapped .so files at lib/{arch}/libassemblies.{arch}.blob.so */
    private fun readStoreSetFromArchiveElfFormat(archive: ArchiveSource) {
        val elfEntries = archive.entryNames
            .filter { it.startsWith("lib/") && it.contains("libassemblies.") && it.endsWith(".blob.so") }
            .sortedBy { abiPreference(it.split('/').getOrNull(1)) }
        for (name in elfEntries) {
            log(AssemblyStoreLogLevel.INFO, "Found ELF-wrapped assembly blob: $name")
            val payload = ElfPayload.extract(archive.read(name))
            if (payload == null) {
                log(AssemblyStoreLogLevel.WARNING, "$name has no '${ElfPayload.SECTION_NAME}' section (or is not ELF64)")
                continue
            }
            // Extract arch from path like lib/arm64-v8a/libassemblies.arm64-v8a.blob.so
            val parts = name.split('/')
            val arch = if (parts.size >= 2) parts[1] else null
            usedElfFormat = true
            addStore(name) { AssemblyStoreReader(payload, arch) }
        }
    }

    private inline fun addStore(entryName: String, create: () -> AssemblyStoreReader) {
        val reader = try {
            create()
        } catch (e: RuntimeException) {
            log(AssemblyStoreLogLevel.ERROR, "Couldn't read assembly store $entryName: ${e.message}")
            return
        }
        storeEntries += entryName
        if (reader.hasGlobalIndex) indexStore = reader
        stores.getOrPut(reader.storeId) { ArrayList() } += reader
        assemblies += reader.assemblies
    }

    companion object {
        /** C# `ReadStoreSetFromArchive(baseName, path, ext)`: `{root}/{baseName}.` per archive type. */
        fun basePathInArchive(archiveExtension: String, baseName: String = "assemblies"): String {
            val root = when (archiveExtension.lowercase().removePrefix(".")) {
                "aab" -> "base/root/assemblies"
                "apk" -> "assemblies"
                "zip" -> "root/assemblies"
                else -> throw IllegalArgumentException("Unrecognized archive extension '$archiveExtension'")
            }
            return "$root/$baseName."
        }

        fun fromArchive(
            archive: ArchiveSource,
            basePathInArchive: String = basePathInArchive("apk"),
            logger: ((AssemblyStoreLogLevel, String) -> Unit)? = null,
        ): AssemblyStoreExplorer = AssemblyStoreExplorer(logger).apply {
            readStoreSetFromArchive(archive, basePathInArchive)
            processStores()
        }

        /** C# `GetStoreArch`: `assemblies.arm64_v8a.blob` -> `arm64_v8a`, `assemblies.blob` -> "". */
        fun getStoreArch(path: String): String {
            val fileName = path.substringAfterLast('/')
            val noExt = fileName.substringBeforeLast('.', fileName)
            val dot = noExt.lastIndexOf('.')
            return if (dot >= 0) noExt.substring(dot + 1) else ""
        }

        internal fun abiPreference(abi: String?): Int = when (abi?.replace('_', '-')) {
            "arm64-v8a" -> 0
            "x86_64", "x86-64" -> 1
            "armeabi-v7a" -> 2
            "x86" -> 3
            else -> 4
        }
    }
}
