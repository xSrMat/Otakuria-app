package eu.kanade.domain.items.chapter.interactor

import eu.kanade.domain.download.manga.interactor.DeleteChapterDownload
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.entries.manga.repository.MangaRepository
import tachiyomi.domain.items.chapter.model.Chapter
import tachiyomi.domain.items.chapter.model.ChapterUpdate
import tachiyomi.domain.items.chapter.repository.ChapterRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SetReadStatus(
    private val downloadPreferences: DownloadPreferences,
    private val deleteDownload: DeleteChapterDownload,
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
) {

    private val mapper = { chapter: Chapter, read: Boolean ->
        ChapterUpdate(
            read = read,
            lastPageRead = if (!read) 0 else null,
            id = chapter.id,
        )
    }

    suspend fun await(read: Boolean, vararg chapters: Chapter): Result = withNonCancellableContext {
        val chaptersToUpdate = chapters.filter {
            when (read) {
                true -> !it.read
                false -> it.read || it.lastPageRead > 0
            }
        }
        if (chaptersToUpdate.isEmpty()) {
            return@withNonCancellableContext Result.NoChapters
        }

        try {
            chapterRepository.updateAllChapters(
                chaptersToUpdate.map { mapper(it, read) },
            )
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            return@withNonCancellableContext Result.InternalError(e)
        }

        if (read && downloadPreferences.removeAfterMarkedAsRead().get()) {
            chaptersToUpdate
                .groupBy { it.mangaId }
                .forEach { (mangaId, chapters) ->
                    deleteDownload.awaitAll(
                        manga = mangaRepository.getMangaById(mangaId),
                        chapters = chapters.toTypedArray(),
                    )
                }
        }

        try {
            val syncManager = uy.kohesive.injekt.Injekt.get<eu.kanade.tachiyomi.data.sync.supabase.SupabaseSyncManager>()
            chaptersToUpdate.map { it.mangaId }.distinct().forEach { mangaId ->
                val allChapters = chapterRepository.getChapterByMangaId(mangaId)
                val remainingRead = allChapters.filter { it.read }
                val maxRead = remainingRead.maxByOrNull { it.chapterNumber }
                if (maxRead != null) {
                    syncManager.updateLastReadForManga(mangaId, maxRead.id.toString(), maxRead.name, maxRead.chapterNumber)
                } else {
                    syncManager.updateLastReadForManga(mangaId, "", "", -1.0)
                }
            }
        } catch (_: Exception) {}

        Result.Success
    }

    suspend fun await(mangaId: Long, read: Boolean): Result = withNonCancellableContext {
        await(
            read = read,
            chapters = chapterRepository
                .getChapterByMangaId(mangaId)
                .toTypedArray(),
        )
    }

    suspend fun await(manga: Manga, read: Boolean) =
        await(manga.id, read)

    sealed interface Result {
        data object Success : Result
        data object NoChapters : Result
        data class InternalError(val error: Throwable) : Result
    }
}
