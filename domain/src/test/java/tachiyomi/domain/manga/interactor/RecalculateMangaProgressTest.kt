package tachiyomi.domain.manga.interactor

import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.repository.MangaProgressRepository

class RecalculateMangaProgressTest {

    private lateinit var recalculateMangaProgress: RecalculateMangaProgress
    private lateinit var mangaProgressRepository: MangaProgressRepository

    @BeforeEach
    fun beforeEach() {
        mangaProgressRepository = mockk()

        recalculateMangaProgress = RecalculateMangaProgress(mangaProgressRepository)
    }

    @Test
    fun `When recalculating stale rows expect only the flagged ones`() = runTest {
        coEvery { mangaProgressRepository.getStaleMangaIds() } returns listOf(1L, 4L)
        coEvery { mangaProgressRepository.recalculateAll(any()) } just Runs

        recalculateMangaProgress.awaitStale() shouldBe 2

        coVerify { mangaProgressRepository.recalculateAll(listOf(1L, 4L)) }
    }

    @Test
    fun `When nothing is stale expect no rows recalculated`() = runTest {
        coEvery { mangaProgressRepository.getStaleMangaIds() } returns emptyList()
        coEvery { mangaProgressRepository.recalculateAll(any()) } just Runs

        recalculateMangaProgress.awaitStale() shouldBe 0
    }

    @Test
    fun `When the recalculation fails expect the rows to stay flagged`() = runTest {
        coEvery { mangaProgressRepository.getStaleMangaIds() } returns listOf(1L)
        coEvery { mangaProgressRepository.recalculateAll(any()) } throws IllegalStateException("boom")

        recalculateMangaProgress.awaitStale() shouldBe 0
    }

    @Test
    fun `When rebuilding everything expect every row flagged before it is recalculated`() = runTest {
        coEvery { mangaProgressRepository.markAllStale() } just Runs
        coEvery { mangaProgressRepository.getStaleMangaIds() } returns listOf(1L, 2L, 3L)
        coEvery { mangaProgressRepository.recalculateAll(any()) } just Runs

        recalculateMangaProgress.awaitAll() shouldBe 3

        coVerifyOrder {
            mangaProgressRepository.markAllStale()
            mangaProgressRepository.getStaleMangaIds()
            mangaProgressRepository.recalculateAll(listOf(1L, 2L, 3L))
        }
    }

    @Test
    fun `When rows have diverged expect them rebuilt without being flagged first`() = runTest {
        coEvery { mangaProgressRepository.getDivergentMangaIds() } returns listOf(2L, 5L)
        coEvery { mangaProgressRepository.recalculateAll(any()) } just Runs

        recalculateMangaProgress.awaitDivergent() shouldBe 2

        coVerify { mangaProgressRepository.recalculateAll(listOf(2L, 5L)) }
        coVerify(exactly = 0) { mangaProgressRepository.markAllStale() }
    }

    @Test
    fun `When nothing has diverged expect no recalculation at all`() = runTest {
        coEvery { mangaProgressRepository.getDivergentMangaIds() } returns emptyList()

        recalculateMangaProgress.awaitDivergent() shouldBe 0

        coVerify(exactly = 0) { mangaProgressRepository.recalculateAll(any()) }
    }

    @Test
    fun `When recalculating one manga fails expect failure reported`() = runTest {
        coEvery { mangaProgressRepository.recalculate(any()) } throws IllegalStateException("boom")

        recalculateMangaProgress.await(1L) shouldBe false
    }
}
