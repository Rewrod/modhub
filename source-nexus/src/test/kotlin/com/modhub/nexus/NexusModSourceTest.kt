package com.modhub.nexus

import com.modhub.core.model.DownloadProgress
import com.modhub.core.model.GameId
import com.modhub.core.model.ModFileCategory
import com.modhub.core.model.ModId
import com.modhub.core.model.SourceGameRef
import com.modhub.core.source.ModListing
import com.modhub.core.source.ModQuery
import com.modhub.core.source.SourceAuthException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NexusModSourceTest {
    private val game = SourceGameRef(GameId("stardew"), "stardewvalley")
    private val dir: File = Files.createTempDirectory("nexus-src").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private class FakeApi : NexusApi {
        override val rateLimit: StateFlow<RateLimitInfo?> = MutableStateFlow(null)
        var premium = false
        var validateCalls = 0
        var linksError: Exception? = null
        val linkCalls = mutableListOf<Pair<String?, String?>>()
        var lists: Map<NexusModList, String> = emptyMap()
        var body: () -> NexusDownloadStream = { error("no body") }
        val opened = mutableListOf<String>()

        override suspend fun validateUser() = NexusUserDto(name = "Ann", isPremium = premium).also { validateCalls++ }
        override suspend fun game(domain: String) = NexusGameDto(domainName = domain)
        override suspend fun listMods(domain: String, list: NexusModList) =
            NexusJson.modList(lists[list] ?: "[]")
        override suspend fun mod(domain: String, modId: Long) = NexusFixtures.contentPatcher()
        override suspend fun files(domain: String, modId: Long) = NexusJson.files(NexusFixtures.FILES_JSON)
        override suspend fun downloadLinks(domain: String, modId: Long, fileId: Long, key: String?, expires: String?): List<NexusDownloadLinkDto> {
            linkCalls += key to expires
            linksError?.let { throw it }
            return NexusJson.downloadLinks(NexusFixtures.DOWNLOAD_LINKS_JSON)
        }
        override suspend fun md5Search(domain: String, md5: String) = emptyList<NexusMd5ResultDto>()
        override suspend fun openDownload(url: String): NexusDownloadStream {
            opened += url
            return body()
        }
    }

    private fun stream(bytes: ByteArray, length: Long? = bytes.size.toLong()) = object : NexusDownloadStream {
        override val contentLength = length
        override val input: InputStream = ByteArrayInputStream(bytes)
        override fun cancel() {}
        override fun close() {}
    }

    private fun source(api: FakeApi, key: String? = "KEY") = NexusModSource(api, { key }, Dispatchers.IO)

    private suspend fun mainFile(src: NexusModSource) = src.getFiles(game, ModId("1915")).first()

    @Test
    fun metadata() = runBlocking {
        val src = source(FakeApi())
        assertEquals("nexus", src.id)
        assertEquals("Nexus Mods", src.displayName)
        assertFalse(src.supportsSearch)
        assertTrue(src.isConfigured())
        assertFalse(source(FakeApi(), key = " ").isConfigured())
        assertFalse(source(FakeApi(), key = null).isConfigured())
    }

    @Test
    fun listLatestUsesEndpointAndDropsHidden() = runBlocking {
        val api = FakeApi().apply { lists = mapOf(NexusModList.TRENDING to NexusFixtures.MOD_LIST_JSON) }
        val mods = source(api).listLatest(game, ModListing.TRENDING)
        assertEquals(listOf("1915"), mods.map { it.id.value })
        assertTrue(source(api).listLatest(game, ModListing.LATEST_ADDED).isEmpty())
    }

    @Test
    fun searchFiltersAllListsAndDeduplicates() = runBlocking {
        val other = """[{"mod_id":5,"name":"Farm Expansion","summary":"bigger farm","status":"published"}]"""
        val api = FakeApi().apply {
            lists = mapOf(
                NexusModList.LATEST_ADDED to NexusFixtures.MOD_LIST_JSON,
                NexusModList.LATEST_UPDATED to NexusFixtures.MOD_LIST_JSON,
                NexusModList.TRENDING to other,
            )
        }
        assertEquals(listOf("1915"), source(api).search(ModQuery(game, "content")).items.map { it.id.value })
        assertEquals(listOf("5"), source(api).search(ModQuery(game, "FARM")).items.map { it.id.value })
        assertEquals(2, source(api).search(ModQuery(game, "")).items.size)
    }

    @Test
    fun getModAndFiles() = runBlocking {
        val src = source(FakeApi())
        assertEquals("Content Patcher", src.getMod(game, ModId("1915")).name)
        val files = src.getFiles(game, ModId("1915"))
        assertEquals(ModFileCategory.MAIN, files.first().category)
        assertEquals("101", src.fileForNxm(game, NxmLink.parse("nxm://stardewvalley/mods/1915/files/101")!!).id)
    }

    @Test
    fun downloadStreamsToDestinationWithProgress() = runBlocking {
        val bytes = ByteArray(700_000) { (it * 7).toByte() }
        val api = FakeApi().apply { body = { stream(bytes) } }
        val src = source(api)
        val dest = File(dir, "sub/cp.zip")
        val events = src.download(game, mainFile(src), dest).toList()
        assertEquals(DownloadProgress.Started, events.first())
        assertEquals(DownloadProgress.Completed(dest), events.last())
        val progress = events.filterIsInstance<DownloadProgress.Progress>()
        assertEquals(DownloadProgress.Progress(0, 700_000), progress.first())
        assertEquals(DownloadProgress.Progress(700_000, 700_000), progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> b.bytesRead > a.bytesRead })
        assertContentEquals(bytes, dest.readBytes())
        assertFalse(File(dest.path + ".part").exists())
        assertEquals(listOf("https://cf-files.nexusmods.com/cdn/1/a.zip?md5=x"), api.opened)
        assertEquals(listOf<Pair<String?, String?>>(null to null), api.linkCalls)
    }

    @Test
    fun downloadPassesNxmKeyAndExpires() = runBlocking {
        val api = FakeApi().apply { body = { stream(byteArrayOf(1, 2, 3), length = null) } }
        val src = source(api)
        val auth = NxmLink.parse("nxm://stardewvalley/mods/1915/files/101?key=K&expires=E&user_id=U")!!.toDownloadAuth()
        val events = src.download(game, mainFile(src), File(dir, "a.zip"), auth).toList()
        assertIs<DownloadProgress.Completed>(events.last())
        assertEquals(listOf<Pair<String?, String?>>("K" to "E"), api.linkCalls)
        // Total falls back to ModFile.sizeBytes when there is no Content-Length.
        assertEquals(1_048_576L, events.filterIsInstance<DownloadProgress.Progress>().first().totalBytes)
    }

    @Test
    fun fallsBackToNextCdnWhenFirstFails() = runBlocking {
        var n = 0
        val api = FakeApi().apply { body = { if (n++ == 0) throw IOException("down") else stream(byteArrayOf(9)) } }
        val src = source(api)
        val events = src.download(game, mainFile(src), File(dir, "b.zip")).toList()
        assertIs<DownloadProgress.Completed>(events.last())
        assertEquals(2, api.opened.size)
    }

    @Test
    fun nonPremium403BecomesPremiumRequired() = runBlocking {
        val api = FakeApi().apply { linksError = NexusDownloadForbiddenException(false, "forbidden") }
        val src = source(api)
        val dest = File(dir, "c.zip")
        val events = src.download(game, mainFile(src), dest).toList()
        val failed = assertIs<DownloadProgress.Failed>(events.last())
        val e = assertIs<NexusPremiumRequiredException>(failed.error)
        assertTrue("nxm://" in e.message!!)
        assertTrue("Mod Manager Download" in e.message!!)
        assertEquals(1, api.validateCalls)
        assertFalse(dest.exists())
        assertEquals(2, events.size) // Started, Failed
    }

    @Test
    fun premium403IsAuthError() = runBlocking {
        val api = FakeApi().apply { premium = true; linksError = NexusDownloadForbiddenException(false, "nope") }
        val src = source(api)
        val failed = src.download(game, mainFile(src), File(dir, "d.zip")).toList().last()
        assertIs<SourceAuthException>(assertIs<DownloadProgress.Failed>(failed).error)
        Unit
    }

    @Test
    fun rejectedNxmKeyIsReportedAsSuch() = runBlocking {
        val api = FakeApi().apply { linksError = NexusDownloadForbiddenException(true, "expired") }
        val src = source(api)
        val failed = src.download(game, mainFile(src), File(dir, "e.zip"), NxmLink("stardewvalley", 1915, 101, key = "k").toDownloadAuth())
            .toList().last()
        assertIs<NexusNxmLinkRejectedException>(assertIs<DownloadProgress.Failed>(failed).error)
        assertEquals(0, api.validateCalls)
    }

    @Test
    fun rateLimitAndTruncationSurfaceAsFailed() = runBlocking {
        val api = FakeApi().apply { linksError = RateLimitedException(RateLimitInfo(hourlyRemaining = 0)) }
        val src = source(api)
        assertIs<RateLimitedException>(assertIs<DownloadProgress.Failed>(src.download(game, mainFile(src), File(dir, "f.zip")).toList().last()).error)

        val api2 = FakeApi().apply { body = { stream(byteArrayOf(1, 2), length = 10) } }
        val src2 = source(api2)
        val dest = File(dir, "g.zip")
        assertIs<IOException>(assertIs<DownloadProgress.Failed>(src2.download(game, mainFile(src2), dest).toList().last()).error)
        assertFalse(dest.exists())
        assertFalse(File(dest.path + ".part").exists())
        Unit
    }

    @Test
    fun cancellingCollectionCancelsABlockedRead() = runBlocking {
        val cancelled = CountDownLatch(1)
        val firstChunkRead = CompletableDeferred<Unit>()
        val api = FakeApi().apply {
            body = {
                object : NexusDownloadStream {
                    override val contentLength: Long? = 1_000_000
                    private var sent = false
                    override val input = object : InputStream() {
                        override fun read(): Int = throw UnsupportedOperationException()
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            if (!sent) { sent = true; return 10 }
                            // Simulates a socket stuck waiting for data until OkHttp's Call.cancel().
                            if (!cancelled.await(10, TimeUnit.SECONDS)) error("never cancelled")
                            throw IOException("Canceled")
                        }
                    }
                    override fun cancel() = cancelled.countDown()
                    override fun close() {}
                }
            }
        }
        val src = source(api)
        val dest = File(dir, "h.zip")
        val file = mainFile(src)
        val job = launch(Dispatchers.Default) {
            src.download(game, file, dest).collect {
                if (it is DownloadProgress.Progress) firstChunkRead.complete(Unit)
            }
        }
        withTimeout(5_000) { firstChunkRead.await() }
        Thread.sleep(100) // let the reader block
        withTimeout(5_000) { job.cancelAndJoinCompat() }
        assertTrue(cancelled.count == 0L, "stream.cancel() should have been called")
        assertFalse(dest.exists())
        // .part is removed on cancellation
        assertFalse(File(dest.path + ".part").exists())
    }

    @Test
    fun collectorExceptionsPropagateInsteadOfBecomingFailed() = runBlocking {
        val api = FakeApi().apply { body = { stream(ByteArray(10)) } }
        val src = source(api)
        val seen = mutableListOf<DownloadProgress>()
        val err = runCatching {
            src.download(game, mainFile(src), File(dir, "i.zip")).collect {
                seen += it
                if (it is DownloadProgress.Progress) throw IllegalStateException("ui bug")
            }
        }.exceptionOrNull()
        assertEquals("ui bug", assertIs<IllegalStateException>(err).message)
        assertTrue(seen.none { it is DownloadProgress.Failed })
        assertFalse(File(dir, "i.zip.part").exists())
    }

    @Test
    fun validateKeyIsCachedPerKey() = runBlocking {
        val api = FakeApi().apply { premium = true }
        var key = "A"
        val src = NexusModSource(api, { key })
        assertTrue(src.validateKey().isPremium)
        src.validateKey()
        assertEquals(1, api.validateCalls)
        key = "B"
        src.validateKey()
        assertEquals(2, api.validateCalls)
        src.validateKey(forceRefresh = true)
        assertEquals(3, api.validateCalls)
        assertEquals(null, src.rateLimit.first())
    }

    private suspend fun kotlinx.coroutines.Job.cancelAndJoinCompat() {
        cancel()
        join()
    }
}
