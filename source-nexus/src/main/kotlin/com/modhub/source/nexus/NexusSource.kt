package com.modhub.source.nexus

/**
 * Placeholder for the Nexus Mods [com.modhub.core.source.ModSource].
 *
 * TODO(source-nexus agent): implement `class NexusSource : ModSource` per docs/ARCHITECTURE.md
 * and docs/RESEARCH.md section 4:
 *  - id = SOURCE_ID ("nexus"); base URL https://api.nexusmods.com/v1/, headers `apikey` + User-Agent
 *  - listLatest -> games/{domain}/mods/latest_added|latest_updated|trending.json
 *  - getFiles -> games/{domain}/mods/{id}/files.json
 *  - download -> download_link.json (premium) or with nxm key/expires passed via DownloadAuth
 *  - supportsSearch = false for v1 (throw SourceUnsupportedException from search)
 *  - read rate-limit headers; never ship an API key in the app
 * Then replace the empty `@Multibinds` set in :app's SourceModule with an `@IntoSet` binding.
 */
object NexusSourcePlaceholder {
    const val SOURCE_ID = "nexus"
    const val BASE_URL = "https://api.nexusmods.com/v1/"
}
