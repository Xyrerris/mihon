package tachiyomi.domain.manga.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import tachiyomi.domain.manga.interactor.RecalculateMangaProgress
import tachiyomi.domain.manga.repository.MangaProgressRepository

/**
 * Turns the flags the staleness triggers write into recalculations, for the life of the process.
 *
 * The triggers only flag, on purpose: recalculating inside them would put the whole definition of
 * progress into the write path of every page read. Something has to do the work afterwards, and now
 * that libraryView reads the table, afterwards has to mean soon -- a flagged row keeps showing its
 * previous counts until it is rebuilt.
 *
 * The flow is conflated rather than debounced. A pass rebuilds everything flagged at the moment it
 * starts, so writes arriving while it runs collapse into the single pass that follows: a library
 * update flagging hundreds of manga costs one pass, and a single chapter marked read has no fixed
 * delay to sit through. [RecalculateMangaProgress.awaitStale] re-reads the flagged ids rather than
 * taking the emitted list, so a row flagged between the emission and the pass is picked up by it
 * instead of waiting for the next one.
 */
@Inject
@SingleIn(AppScope::class)
class MangaProgressMaintainer(
    private val mangaProgressRepository: MangaProgressRepository,
    private val recalculateMangaProgress: RecalculateMangaProgress,
) {

    fun init(scope: CoroutineScope): Job {
        return mangaProgressRepository.getStaleMangaIdsAsFlow()
            .filter { it.isNotEmpty() }
            .conflate()
            .onEach { recalculateMangaProgress.awaitStale() }
            .flowOn(Dispatchers.IO)
            .launchIn(scope)
    }
}
