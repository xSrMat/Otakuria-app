package eu.kanade.domain.entries.anime.interactor

import tachiyomi.domain.entries.anime.interactor.AnimeFetchInterval
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.model.AnimeUpdate
import tachiyomi.domain.entries.anime.repository.AnimeRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import java.time.ZonedDateTime

class UpdateAnime(
    private val animeRepository: AnimeRepository,
    private val animeFetchInterval: AnimeFetchInterval,
) {

    suspend fun await(animeUpdate: AnimeUpdate): Boolean {
        return animeRepository.updateAnime(animeUpdate)
    }

    suspend fun awaitAll(animeUpdates: List<AnimeUpdate>): Boolean {
        val result = animeRepository.updateAllAnime(animeUpdates)
        if (result) {
            try {
                val syncManager = Injekt.get<eu.kanade.tachiyomi.data.sync.supabase.SupabaseSyncManager>()
                animeUpdates.forEach { u ->
                    if (u.favorite == false) {
                        syncManager.deleteAnimeFromCloud(u.id)
                    }
                }
            } catch (_: Exception) {}
        }
        return result
    }

    suspend fun awaitUpdateFetchInterval(
        anime: Anime,
        dateTime: ZonedDateTime = ZonedDateTime.now(),
        window: Pair<Long, Long> = animeFetchInterval.getWindow(dateTime),
    ): Boolean {
        return animeRepository.updateAnime(
            animeFetchInterval.toAnimeUpdate(anime, dateTime, window),
        )
    }

    suspend fun awaitUpdateLastUpdate(animeId: Long): Boolean {
        return animeRepository.updateAnime(AnimeUpdate(id = animeId, lastUpdate = Instant.now().toEpochMilli()))
    }

    suspend fun awaitUpdateCoverLastModified(animeId: Long): Boolean {
        return animeRepository.updateAnime(
            AnimeUpdate(id = animeId, coverLastModified = Instant.now().toEpochMilli()),
        )
    }

    suspend fun awaitUpdateBackgroundLastModified(animeId: Long): Boolean {
        return animeRepository.updateAnime(
            AnimeUpdate(id = animeId, backgroundLastModified = Instant.now().toEpochMilli()),
        )
    }

    suspend fun awaitUpdateFavorite(animeId: Long, favorite: Boolean): Boolean {
        val dateAdded = when (favorite) {
            true -> Instant.now().toEpochMilli()
            false -> 0
        }
        val result = animeRepository.updateAnime(
            AnimeUpdate(id = animeId, favorite = favorite, dateAdded = dateAdded),
        )
        if (result) {
            try {
                val syncManager = Injekt.get<eu.kanade.tachiyomi.data.sync.supabase.SupabaseSyncManager>()
                if (favorite) {
                    syncManager.triggerSync()
                } else {
                    syncManager.deleteAnimeFromCloud(animeId)
                }
            } catch (_: Exception) {}
        }
        return result
    }
}
