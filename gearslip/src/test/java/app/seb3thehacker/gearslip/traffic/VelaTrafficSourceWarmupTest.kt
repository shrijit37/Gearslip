package app.seb3thehacker.gearslip.traffic

import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.UnknownHostException

/** A failed consent handshake must be retried; it must not latch the session as warmed. */
class VelaTrafficSourceWarmupTest {

    @Test
    fun `failed warmup is retried instead of latching warmed`() = runBlocking {
        var lookups = 0
        val failingDns = Dns { _ ->
            lookups++
            throw UnknownHostException("blocked for test")
        }
        val source = VelaTrafficSource(dns = failingDns)

        source.warmup()
        source.warmup()

        assertEquals("a failed handshake must not mark the session warmed", 2, lookups)
    }
}
