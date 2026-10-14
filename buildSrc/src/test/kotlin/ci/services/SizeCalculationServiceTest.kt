package ci.services

import ci.Labels
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SizeCalculationServiceTest {

    private val service = SizeCalculationService(OkHttpClient())

    @Test
    fun `maps file counts to the correct size bucket at every boundary`() {
        assertEquals(Labels.SIZE_XS, service.mapFileCountToLabel(0))
        assertEquals(Labels.SIZE_XS, service.mapFileCountToLabel(9))
        assertEquals(Labels.SIZE_S, service.mapFileCountToLabel(10))
        assertEquals(Labels.SIZE_S, service.mapFileCountToLabel(49))
        assertEquals(Labels.SIZE_M, service.mapFileCountToLabel(50))
        assertEquals(Labels.SIZE_M, service.mapFileCountToLabel(199))
        assertEquals(Labels.SIZE_L, service.mapFileCountToLabel(200))
        assertEquals(Labels.SIZE_L, service.mapFileCountToLabel(499))
        assertEquals(Labels.SIZE_XL, service.mapFileCountToLabel(500))
        assertEquals(Labels.SIZE_XL, service.mapFileCountToLabel(10_000))
    }
}
