package com.modhub.nexus

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Pure JSON decoding for Nexus responses (no OkHttp), so it is unit-testable on a bare JVM.
 */
object NexusJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun user(body: String): NexusUserDto = json.decodeFromString(NexusUserDto.serializer(), body)
    fun game(body: String): NexusGameDto = json.decodeFromString(NexusGameDto.serializer(), body)
    fun mod(body: String): NexusModDto = json.decodeFromString(NexusModDto.serializer(), body)
    fun modList(body: String): List<NexusModDto> = json.decodeFromString(ListSerializer(NexusModDto.serializer()), body)
    fun files(body: String): NexusFilesResponseDto = json.decodeFromString(NexusFilesResponseDto.serializer(), body)
    fun downloadLinks(body: String): List<NexusDownloadLinkDto> =
        json.decodeFromString(ListSerializer(NexusDownloadLinkDto.serializer()), body)
    fun md5Search(body: String): List<NexusMd5ResultDto> =
        json.decodeFromString(ListSerializer(NexusMd5ResultDto.serializer()), body)

    /** Best-effort `message` from an error body; null if it isn't the usual JSON shape. */
    fun errorMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return runCatching { json.decodeFromString(NexusErrorDto.serializer(), body).message }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }
}
