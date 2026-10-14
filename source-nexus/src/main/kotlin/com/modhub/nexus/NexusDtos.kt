package com.modhub.nexus

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Wire DTOs for the Nexus Mods v1 REST API. Every field is optional/defaulted and the
 * decoder ignores unknown keys (see [NexusJson]) so API additions or missing fields on
 * hidden/removed mods never break parsing.
 */

/** `GET users/validate.json`. */
@Serializable
data class NexusUserDto(
    @SerialName("user_id") val userId: Long? = null,
    val name: String? = null,
    @SerialName("is_premium") val isPremium: Boolean = false,
    @SerialName("is_supporter") val isSupporter: Boolean = false,
    @SerialName("profile_url") val profileUrl: String? = null,
)

/** `GET games/{domain}.json` (subset). */
@Serializable
data class NexusGameDto(
    val id: Long? = null,
    val name: String? = null,
    @SerialName("domain_name") val domainName: String? = null,
    @SerialName("nexusmods_url") val nexusmodsUrl: String? = null,
    val mods: Long? = null,
    val downloads: Long? = null,
)

@Serializable
data class NexusModUserDto(
    @SerialName("member_id") val memberId: Long? = null,
    val name: String? = null,
)

/** One mod: `mods/{id}.json` and the elements of `latest_added` / `latest_updated` / `trending`. */
@Serializable
data class NexusModDto(
    @SerialName("mod_id") val modId: Long,
    @SerialName("game_id") val gameId: Long? = null,
    @SerialName("domain_name") val domainName: String? = null,
    val name: String? = null,
    val summary: String? = null,
    val description: String? = null,
    val version: String? = null,
    val author: String? = null,
    @SerialName("uploaded_by") val uploadedBy: String? = null,
    val user: NexusModUserDto? = null,
    @SerialName("picture_url") val pictureUrl: String? = null,
    @SerialName("mod_downloads") val modDownloads: Long? = null,
    @SerialName("mod_unique_downloads") val modUniqueDownloads: Long? = null,
    @SerialName("endorsement_count") val endorsementCount: Long? = null,
    @SerialName("created_timestamp") val createdTimestamp: Long? = null,
    @SerialName("updated_timestamp") val updatedTimestamp: Long? = null,
    @SerialName("contains_adult_content") val containsAdultContent: Boolean = false,
    /** "published", "hidden", "not_published", "removed", "wastebinned", ... */
    val status: String? = null,
    val available: Boolean = true,
)

/** One file in `mods/{id}/files.json` (also `file_details` in md5 search). */
@Serializable
data class NexusFileDto(
    @SerialName("file_id") val fileId: Long,
    val name: String? = null,
    val version: String? = null,
    @SerialName("mod_version") val modVersion: String? = null,
    @SerialName("category_id") val categoryId: Int? = null,
    @SerialName("category_name") val categoryName: String? = null,
    @SerialName("is_primary") val isPrimary: Boolean = false,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("size_in_bytes") val sizeInBytes: Long? = null,
    @SerialName("size_kb") val sizeKb: Long? = null,
    @SerialName("uploaded_timestamp") val uploadedTimestamp: Long? = null,
    val description: String? = null,
    val md5: String? = null,
)

@Serializable
data class NexusFileUpdateDto(
    @SerialName("old_file_id") val oldFileId: Long? = null,
    @SerialName("new_file_id") val newFileId: Long? = null,
    @SerialName("uploaded_timestamp") val uploadedTimestamp: Long? = null,
)

/** `GET mods/{id}/files.json`. */
@Serializable
data class NexusFilesResponseDto(
    val files: List<NexusFileDto> = emptyList(),
    @SerialName("file_updates") val fileUpdates: List<NexusFileUpdateDto> = emptyList(),
)

/** One CDN entry of `download_link.json` (the response is a JSON array of these). */
@Serializable
data class NexusDownloadLinkDto(
    val name: String? = null,
    @SerialName("short_name") val shortName: String? = null,
    @SerialName("URI") val uri: String,
)

/** One element of `mods/md5_search/{md5}.json`. */
@Serializable
data class NexusMd5ResultDto(
    val mod: NexusModDto,
    @SerialName("file_details") val fileDetails: NexusFileDto,
)

/** Error body, e.g. `{"code":403,"message":"..."}`. */
@Serializable
data class NexusErrorDto(
    val code: Int? = null,
    val message: String? = null,
)
