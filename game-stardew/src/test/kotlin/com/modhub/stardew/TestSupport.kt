package com.modhub.stardew

import com.modhub.core.plugin.GameContext
import com.modhub.core.plugin.PackageInspector
import com.modhub.core.storage.FileStorageBackend
import com.modhub.core.storage.StoragePath
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

class FakePackages(private val installed: Map<String, String?> = emptyMap()) : PackageInspector {
    override fun isInstalled(packageName: String) = packageName in installed
    override fun versionName(packageName: String) = installed[packageName]
}

/** Fresh temp dir mapped as the storage root, with `/storage/emulated/0` as external storage. */
abstract class StardewTestBase {
    lateinit var tmp: File
    lateinit var storage: FileStorageBackend
    val plugin = StardewPlugin()
    val ext: StoragePath = StoragePath.of("/storage/emulated/0")
    val modsDir: StoragePath = ext.resolve("StardewValley/Mods")

    @BeforeTest
    fun setUpTemp() {
        tmp = Files.createTempDirectory("stardew-test").toFile()
        storage = FileStorageBackend(tmp)
    }

    @AfterTest
    fun tearDownTemp() {
        tmp.deleteRecursively()
    }

    fun context(
        packages: Map<String, String?> = emptyMap(),
        override: StoragePath? = null,
    ) = GameContext(storage, FakePackages(packages), override, ext)

    fun file(path: StoragePath): File = storage.toFile(path)

    fun write(path: StoragePath, text: String) {
        val f = file(path)
        f.parentFile.mkdirs()
        f.writeText(text)
    }
}

fun manifestJson(
    id: String,
    name: String = id,
    version: String = "1.0.0",
    extra: String = "",
) = """{ "Name": "$name", "Author": "Tester", "Version": "$version", "UniqueID": "$id" $extra }"""

/** Builds an in-memory zip. Keys ending in '/' become directory entries. */
fun zipOf(vararg entries: Pair<String, String>): ByteArray {
    val bos = ByteArrayOutputStream()
    ZipOutputStream(bos).use { zip ->
        for ((name, content) in entries) {
            zip.putNextEntry(ZipEntry(name))
            if (!name.endsWith("/")) zip.write(content.toByteArray())
            zip.closeEntry()
        }
    }
    return bos.toByteArray()
}
