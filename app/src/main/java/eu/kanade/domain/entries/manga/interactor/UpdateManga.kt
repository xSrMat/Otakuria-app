package eu.kanade.domain.entries.manga.interactor

import eu.kanade.domain.entries.manga.model.hasCustomCover
import eu.kanade.tachiyomi.data.cache.MangaCoverCache
import eu.kanade.tachiyomi.source.model.SManga
import tachiyomi.domain.entries.manga.interactor.MangaFetchInterval
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.entries.manga.model.MangaUpdate
import tachiyomi.domain.entries.manga.repository.MangaRepository
import tachiyomi.source.local.entries.manga.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import java.time.ZonedDateTime

class UpdateManga(
    private val mangaRepository: MangaRepository,
    private val mangaFetchInterval: MangaFetchInterval,
) {

    suspend fun await(mangaUpdate: MangaUpdate): Boolean {
        return mangaRepository.updateManga(mangaUpdate)
    }

    suspend fun awaitAll(mangaUpdates: List<MangaUpdate>): Boolean {
        val result = mangaRepository.updateAllManga(mangaUpdates)
        if (result) {
            try {
                val syncManager = Injekt.get<eu.kanade.tachiyomi.data.sync.supabase.SupabaseSyncManager>()
                mangaUpdates.forEach { u ->
                    if (u.favorite == false) {
                        syncManager.deleteMangaFromCloud(u.id)
                    }
                }
            } catch (_: Exception) {}
        }
        return result
    }

    suspend fun awaitUpdateFromSource(
        localManga: Manga,
        remoteManga: SManga,
        manualFetch: Boolean,
        coverCache: MangaCoverCache = Injekt.get(),
    ): Boolean {
        val remoteTitle = try {
            remoteManga.title
        } catch (_: UninitializedPropertyAccessException) {
            ""
        }

        // if the manga isn't a favorite, set its title from source and update in db
        val title = if (remoteTitle.isEmpty() || localManga.favorite) null else remoteTitle

        val coverLastModified =
            when {
                // Never refresh covers if the url is empty to avoid "losing" existing covers
                remoteManga.thumbnail_url.isNullOrEmpty() -> null
                !manualFetch && localManga.thumbnailUrl == remoteManga.thumbnail_url -> null
                localManga.isLocal() -> Instant.now().toEpochMilli()
                localManga.hasCustomCover(coverCache) -> {
                    coverCache.deleteFromCache(localManga, false)
                    null
                }
                else -> {
                    coverCache.deleteFromCache(localManga, false)
                    Instant.now().toEpochMilli()
                }
            }

        val thumbnailUrl = remoteManga.thumbnail_url?.takeIf { it.isNotEmpty() }

        return mangaRepository.updateManga(
            MangaUpdate(
                id = localManga.id,
                title = title,
                coverLastModified = coverLastModified,
                author = remoteManga.author,
                artist = remoteManga.artist,
                description = remoteManga.description,
                genre = remoteManga.getGenres(),
                thumbnailUrl = thumbnailUrl,
                status = remoteManga.status.toLong(),
                updateStrategy = remoteManga.update_strategy,
                initialized = true,
            ),
        )
    }

    suspend fun awaitUpdateFetchInterval(
        manga: Manga,
        dateTime: ZonedDateTime = ZonedDateTime.now(),
        window: Pair<Long, Long> = mangaFetchInterval.getWindow(dateTime),
    ): Boolean {
        return mangaRepository.updateManga(
            mangaFetchInterval.toMangaUpdate(manga, dateTime, window),
        )
    }

    suspend fun awaitUpdateLastUpdate(mangaId: Long): Boolean {
        return mangaRepository.updateManga(MangaUpdate(id = mangaId, lastUpdate = Instant.now().toEpochMilli()))
    }

    suspend fun awaitUpdateCoverLastModified(mangaId: Long): Boolean {
        return mangaRepository.updateManga(MangaUpdate(id = mangaId, coverLastModified = Instant.now().toEpochMilli()))
    }

    suspend fun awaitUpdateFavorite(mangaId: Long, favorite: Boolean): Boolean {
        val dateAdded = when (favorite) {
            true -> Instant.now().toEpochMilli()
            false -> 0
        }
        val result = mangaRepository.updateManga(
            MangaUpdate(id = mangaId, favorite = favorite, dateAdded = dateAdded),
        )
        if (result) {
            try {
                val syncManager = Injekt.get<eu.kanade.tachiyomi.data.sync.supabase.SupabaseSyncManager>()
                if (favorite) {
                    syncManager.triggerSync()
                } else {
                    syncManager.deleteMangaFromCloud(mangaId)
                }
            } catch (_: Exception) {}
        }
        return result
    }
}
