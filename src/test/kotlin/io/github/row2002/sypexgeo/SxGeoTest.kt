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

/** Needs testdata/SxGeo.dat and testdata/SxGeoCity.dat from https://sypexgeo.net/ru/download/ */
class SxGeoTest {
    private fun db(name: String): SxGeo {
        val file = File("testdata/$name")
        assumeTrue(file.exists(), "testdata/$name not found, skipping")
        return SxGeo(file)
    }

    private val city by lazy { db("SxGeoCity.dat") }
    private val country by lazy { db("SxGeo.dat") }

    @Test
    fun `country database`() {
        assertFalse(country.info.hasCities)
        assertEquals("RU", country.country("77.88.8.8"))
        assertEquals("US", country.country("8.8.8.8"))
        val info = assertNotNull(country.lookup("77.88.8.8"))
        assertEquals("RU", info.country.iso)
        assertNull(info.city)
    }

    @Test
    fun `city database`() {
        assertTrue(city.info.hasCities)
        val info = assertNotNull(city.lookup("93.73.35.74"))
        assertEquals("UA", info.country.iso)
        assertEquals("Ukraine", info.country.nameEn)
        assertEquals("Украина", info.country.nameRu)
        assertEquals("Kyiv", info.region?.nameEn)
        assertEquals("Kyiv", info.city?.nameEn)
        assertEquals("Киев", info.city?.nameRu)
        assertEquals(50.45, info.city!!.lat, 0.1)
        assertEquals(30.52, info.city!!.lon, 0.1)
        assertFalse("region_seek" in info.city!!.fields)

        assertEquals("RU", city.country("77.88.8.8"))
        assertEquals(185, city.countryId("77.88.8.8"))
        assertEquals("Moscow", city.lookup("77.88.8.8")?.city?.nameEn)
    }

    @Test
    fun `lookup and country agree when a region points to another country`() {
        // Asuncion (PY) whose region record belongs to GB in the current database
        for (ip in listOf("77.88.8.8", "181.121.37.85", "177.250.87.182")) {
            assertEquals(city.country(ip), city.lookup(ip)?.country?.iso, ip)
        }
    }

    @Test
    fun `Inet4Address overload`() {
        val address = InetAddress.getByAddress(byteArrayOf(77, 88, 8, 8)) as Inet4Address
        assertEquals("RU", city.country(address))
        assertEquals(city.lookup("77.88.8.8"), city.lookup(address))
    }

    @Test
    fun `reserved and malformed addresses`() {
        for (ip in listOf("0.0.0.0", "10.1.2.3", "127.0.0.1", "255.255.255.255", "", "1.2.3", "1.2.3.4.5",
            "256.1.1.1", "-1.2.3.4", "+1.2.3.4", "1..2.3", "1.2.3.", "a.b.c.d", "::1", "2001:db8::1", "example.com",
            "77.88.8.8, 10.0.0.1", null)) {
            assertNull(city.lookup(ip), ip)
            assertNull(city.country(ip), ip)
            assertNull(country.country(ip), ip)
        }
        assertEquals("RU", city.country(" 77.88.8.8 "))
    }

    @Test
    fun `random addresses never throw`() {
        val random = Random(42)
        repeat(200_000) {
            val ip = List(4) { random.nextInt(256) }.joinToString(".")
            city.lookup(ip)?.let { assertTrue(it.country.iso.length == 2, ip) }
            country.country(ip)
        }
    }

    @Test
    fun `rejects non Sypex Geo data`() {
        assertFailsWith<IllegalArgumentException> { SxGeo(ByteArray(100)) }
        assertFailsWith<IllegalArgumentException> { SxGeo("SxG".toByteArray()) }
        val full = File("testdata/SxGeoCity.dat").readBytes()
        for (size in listOf(60, 1000, full.size / 2)) {
            assertFailsWith<IllegalArgumentException>("size $size") { SxGeo(full.copyOf(size)) }
        }
    }
}
