package io.github.row2002.sypexgeo

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.net.InetAddress
import java.net.Inet4Address
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests on the real databases: testdata/SxGeo.dat and testdata/SxGeoCity.dat from
 * https://sypexgeo.net/ru/download/ (each test is skipped when its file is missing).
 * Only long-stable facts are asserted, so monthly database updates do not break them.
 */
class SxGeoTest {
    private fun file(name: String) = File("testdata/$name").also { assumeTrue(it.exists(), "testdata/$name not found, skipping") }

    private val city by lazy { SxGeo(file("SxGeoCity.dat")) }
    private val country by lazy { SxGeo(file("SxGeo.dat")) }

    @Test
    fun `country database`() {
        assertFalse(country.info.hasCities)
        assertEquals("SxGeo Country", country.info.type)
        assertEquals("RU", country.country("77.88.8.8"))
        assertEquals(185, country.countryId("77.88.8.8"))
        assertEquals("US", country.country("8.8.8.8"))
        val info = assertNotNull(country.lookup("77.88.8.8"))
        assertEquals(Country(185, "RU"), info.country)
        assertNull(info.city)
        assertNull(info.region)
    }

    @Test
    fun `city database`() {
        assertTrue(city.info.hasCities)
        val info = assertNotNull(city.lookup("93.73.35.74"))
        assertEquals("UA", info.country.iso)
        assertEquals("Ukraine", info.country.nameEn)
        assertEquals("Украина", info.country.nameRu)
        val region = assertNotNull(info.region)
        assertTrue(region.iso.startsWith("UA-"), region.iso)
        val kyiv = assertNotNull(info.city)
        assertTrue(kyiv.nameEn in setOf("Kyiv", "Kiev"), kyiv.nameEn)
        assertEquals("Киев", kyiv.nameRu)
        assertEquals(50.45, kyiv.lat, 0.5)
        assertEquals(30.52, kyiv.lon, 0.5)
        assertFalse("region_seek" in kyiv.fields || "country_id" in kyiv.fields)

        assertEquals("RU", city.country("77.88.8.8"))
        assertEquals(185, city.countryId("77.88.8.8"))
        assertEquals("RU", city.lookup("77.88.8.8")?.country?.iso)
        assertEquals("US", city.country("8.8.8.8"))
    }

    @Test
    fun `InetAddress overloads and IPv4-mapped IPv6`() {
        val address = InetAddress.getByAddress(byteArrayOf(77, 88, 8, 8)) as Inet4Address
        assertEquals("RU", city.country(address))
        assertEquals(city.lookup("77.88.8.8"), city.lookup(address))
        val mapped = InetAddress.getByName("::ffff:93.73.35.74")
        assertEquals("UA", city.country(mapped))
        assertEquals(city.countryId("93.73.35.74"), city.countryId(mapped))
        assertEquals(city.lookup("93.73.35.74"), city.lookup(mapped))
        assertEquals(city.lookup("93.73.35.74"), city.lookup("::FFFF:93.73.35.74"))
        assertEquals("RU", country.country(InetAddress.getByName("::ffff:77.88.8.8")))
        assertNull(city.lookup(InetAddress.getByName("2a02:6b8::feed:0ff")))
    }

    @Test
    fun `reserved and malformed addresses`() {
        for (ip in listOf("0.0.0.0", "10.1.2.3", "127.0.0.1", "255.255.255.255", "", "1.2.3", "1.2.3.4.5", "256.1.1.1",
            "-1.2.3.4", "+1.2.3.4", "1..2.3", "1.2.3.", "a.b.c.d", "::1", "2001:db8::1", "example.com", "08.8.8.8",
            "77.88.8.8, 10.0.0.1", null)) {
            assertNull(city.lookup(ip), ip)
            assertNull(city.country(ip), ip)
            assertNull(country.country(ip), ip)
        }
        assertEquals("RU", city.country(" 77.88.8.8 "))
    }

    @Test
    fun `random addresses agree across methods`() {
        val random = Random(42)
        var withCity = 0
        repeat(200_000) {
            val ip = List(4) { random.nextInt(256) }.joinToString(".")
            val info = city.lookup(ip)
            val id = city.countryId(ip)
            assertEquals(id, info?.country?.id?.takeIf { it > 0 }, ip)
            assertEquals(city.country(ip), id?.let { info?.country?.iso }, ip)
            if (info != null && info.country.id > 0) {
                assertEquals(2, info.country.iso.length, ip)
                // the country always comes with its record (names and coordinates), even when the region is missing
                assertTrue(info.country.nameEn.isNotEmpty(), ip)
            }
            if (info?.city != null) withCity++
            val iso = country.country(ip)
            assertEquals(iso, country.lookup(ip)?.country?.iso, ip)
            assertTrue(iso == null || iso.length == 2, ip)
        }
        assertTrue(withCity > 10_000, "only $withCity IPs with a city")
    }

    @Test
    fun `rejects truncated databases`() {
        val full = file("SxGeoCity.dat").readBytes()
        for (size in listOf(60, 1000, full.size / 2, full.size - 1)) {
            assertFailsWith<IllegalArgumentException>("size $size") { SxGeo(full.copyOf(size)) }
        }
    }
}
