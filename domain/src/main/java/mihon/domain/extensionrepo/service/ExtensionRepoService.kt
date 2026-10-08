package mihon.domain.extensionrepo.service

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.domain.extensionrepo.model.ExtensionRepo
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat

class ExtensionRepoService(
    networkHelper: NetworkHelper,
    private val json: Json,
) {
    val client = networkHelper.client

    suspend fun fetchRepoDetails(
        repo: String,
    ): ExtensionRepo? {
        val cleanRepo = repo
            .removeSuffix("/index.min.json")
            .removeSuffix("/index.pb")
            .removeSuffix("/repo.json")
            .trimEnd('/')

        return withIOContext {
            try {
                with(json) {
                    client.newCall(GET("$cleanRepo/repo.json"))
                        .awaitSuccess()
                        .parseAs<ExtensionRepoMetaDto>()
                        .toExtensionRepo(baseUrl = cleanRepo)
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Failed to fetch repo.json from $cleanRepo, using default metadata" }
                try {
                    // Fallback to minimal repo so user can still add repository
                    val repoName = cleanRepo.substringAfterLast('/').ifBlank { "Manga Repo" }
                    ExtensionRepo(
                        baseUrl = cleanRepo,
                        name = repoName,
                        shortName = repoName,
                        website = cleanRepo,
                        signingKeyFingerprint = "",
                    )
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
}
