package tachiyomi.domain.manga.service

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.RecalculateMangaProgress
import tachiyomi.domain.manga.repository.MangaProgressRepository

class MangaProgressMaintainerTest {

    private lateinit var mangaProgressMaintainer: MangaProgressMaintainer
    private lateinit var mangaProgressRepository: MangaProgressRepository

    @BeforeEach
    fun beforeEach() {
        mangaProgressRepository = mockk()

        mangaProgressMaintainer = MangaProgressMaintainer(
            mangaProgressRepository = mangaProgressRepository,
            recalculateMangaProgress = RecalculateMangaProgress(mangaProgressRepository),
        )
    }

    // The flows below are finite, so joining the job the collection runs in is what waits for the
    // pass; the pass itself is on a dispatcher of its own.
    @Test
    fun `When rows are flagged expect them recalculated`() = runBlocking {
        every { mangaProgressRepository.getStaleMangaIdsAsFlow() } returns flowOf(listOf(1L, 4L))
        coEvery { mangaProgressRepository.getStaleMangaIds() } returns listOf(1L, 4L)
        coEvery { mangaProgressRepository.recalculateAll(any()) } just Runs

        mangaProgressMaintainer.init(this).join()

        coVerify(exactly = 1) { mangaProgressRepository.recalculateAll(listOf(1L, 4L)) }
    }

    @Test
    fun `When nothing is flagged expect no pass at all`() = runBlocking {
        every { mangaProgressRepository.getStaleMangaIdsAsFlow() } returns flowOf(emptyList())

        mangaProgressMaintainer.init(this).join()

        coVerify(exactly = 0) { mangaProgressRepository.getStaleMangaIds() }
    }
}
