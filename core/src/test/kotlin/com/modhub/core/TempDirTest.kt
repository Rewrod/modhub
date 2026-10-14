package com.modhub.core

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

/**
 * Base class giving each test a fresh temp directory. Uses kotlin.test lifecycle
 * annotations (not JUnit 5 @TempDir) so tests run on any kotlin-test backend.
 */
abstract class TempDirTest {
    lateinit var tmp: File

    @BeforeTest
    fun createTempDir() {
        tmp = Files.createTempDirectory("modhub-test").toFile()
    }

    @AfterTest
    fun deleteTempDir() {
        tmp.deleteRecursively()
    }
}
