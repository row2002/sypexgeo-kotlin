package io.github.row2002.sypexgeo

import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.file.Files
import java.time.Instant
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Hermetic tests on databases written by [TestDb]; every answer is checked against a brute-force reference. */
class SyntheticDbTest {

    /** Checks country/countryId/lookup at every boundary IP of [db] against the range list. */
    private fun verify(db: TestDb, label: String, geo: SxGeo = SxGeo(db.build()), minFound: Int = 100) {
        var found = 0
        for (ip in db.probes()) {
            val s = long2ip(ip)
            val (id, iso, info) = db.expected(ip)
            assertEquals(id, geo.countryId(s), "$label countryId($s)")
            assertEquals(iso, geo.country(s), "$label country($s)")
            assertEquals(info, geo.lookup(s), "$label lookup($s)")
            // country() and countryId() read only the country id (fast path) but must agree with lookup()
            assertEquals(info?.country?.id?.takeIf { it > 0 }, id, "$label countryId vs lookup($s)")
            assertEquals(id?.let { info?.country?.iso?.takeIf { it.isNotEmpty() } }, iso, "$label country vs lookup($s)")
            val address = InetAddress.getByAddress(ByteArray(4) { (ip ushr (24 - 8 * it)).toByte() })
            assertEquals(id, geo.countryId(address), "$label countryId(InetAddress $s)")
            assertEquals(iso, geo.country(address), "$label country(InetAddress $s)")
            assertEquals(info, geo.lookup(address), "$label lookup(InetAddress $s)")
            assertEquals(info, geo.lookup("::ffff:$s"), "$label lookup(::ffff:$s)")
            if (info != null) found++
        }
        assertTrue(found >= minFound, "$label: only $found IPs found")
    }

    @Test
    fun `country databases`() {
        verify(TestDb.random(city = false, seed = 1, rangeLen = 16), "empty_only")
        verify(TestDb.random(city = false, seed = 2, rangeLen = 7, split = Split.ALWAYS), "split always")
        verify(TestDb.random(city = false, seed = 3, rangeLen = 16, split = Split.NEVER), "split never")
        verify(TestDb.random(city = false, seed = 4, ranges = 400, rangeLen = 9, idLen = 2, charset = 0), "id_len 2")
    }

    @Test
    fun `city databases`() {
        verify(TestDb.random(city = true, seed = 5, rangeLen = 16), "utf-8")
        verify(TestDb.random(city = true, seed = 6, rangeLen = 9, charset = 2, sentinel = false), "cp1251")
        verify(TestDb.random(city = true, seed = 7, ranges = 350, rangeLen = 12, charset = 1, maxPack = true), "latin1 max")
        verify(TestDb.random(city = true, seed = 8, rangeLen = 16, split = Split.NEVER), "split never")
        verify(TestDb.random(city = true, seed = 9, rangeLen = 5, idLen = 4, split = Split.EMPTY_ONLY), "id_len 4")
        verify(TestDb.random(city = true, seed = 30, rangeLen = 11, pack = SLOW_PACK), "country id after a string")
        verify(TestDb.random(city = true, seed = 31, rangeLen = 13, charset = 2, pack = OFFSET_PACK), "country id at an offset")
    }

    @Test
    fun `country fast path reads only the country id`() {
        // the last city record loses the NUL of its last string: lookup() fails, country() does not need that string
        // unless the country id comes after a variable-size field (then the whole record is decoded)
        for ((pack, fast) in listOf(CITY_PACK to true, OFFSET_PACK to true, SLOW_PACK to false)) {
            val db = TestDb(city = true, pack = pack)
            db.countries.add(mapOf("id" to 222, "iso" to "UA", "name_en" to "Ukraine", "name_ru" to "Украина"))
            db.regions["r"] = TestDb.RegionDef(mapOf("id" to 1, "iso" to "UA-30"), 222)
            db.cities["k"] = TestDb.CityDef(mapOf("id" to 3, "name_en" to "Kyiv", "name_ru" to "Киев"), "r", 222)
            db.cities["last"] = TestDb.CityDef(mapOf("id" to 6, "name_en" to "Lviv", "name_ru" to "Львов"), "r", 222)
            db.range("1.2.3.0", "1.2.3.255", Target.CityAt("k"))
            db.range("1.2.4.0", "1.2.4.255", Target.CityAt("last"))
            db.range("1.2.5.0", "1.2.5.255", Target.CountryAt(222))
            val bytes = db.build()
            val geo = SxGeo(bytes.copyOf().also { it[it.size - 1] = 'x'.code.toByte() })
            assertEquals("Kyiv", geo.lookup("1.2.3.4")?.city?.nameEn)
            assertEquals("UA", geo.country("1.2.5.4"))
            assertCorrupted { geo.lookup("1.2.4.4") }
            if (fast) {
                assertEquals("UA", geo.country("1.2.4.4"))
                assertEquals(222, geo.countryId("1.2.4.4"))
            } else {
                assertCorrupted { geo.country("1.2.4.4") }
                assertCorrupted { geo.countryId("1.2.4.4") }
            }
        }
    }

    @Test
    fun `country fast path reads ids of any size`() {
        // ids beyond one byte (no ISO code): 'S' for country records, 'I' for cities in OFFSET_PACK
        val db = TestDb(city = true, pack = OFFSET_PACK)
        db.countries.add(mapOf("id" to 1234, "name_en" to "Big"))
        db.cities["k"] = TestDb.CityDef(mapOf("id" to 3, "name_en" to "Kyiv"), null, 70_000)
        db.range("1.2.3.0", "1.2.3.255", Target.CountryAt(1234))
        db.range("1.2.4.0", "1.2.4.255", Target.CityAt("k"))
        val geo = SxGeo(db.build())
        assertEquals(1234, geo.countryId("1.2.3.4"))
        assertEquals(1234, geo.lookup("1.2.3.4")?.country?.id)
        assertEquals(70_000, geo.countryId("1.2.4.4"))
        assertEquals(70_000, geo.lookup("1.2.4.4")?.country?.id)
        assertNull(geo.country("1.2.4.4"))
    }

    @Test
    fun `country fast path keeps the bounds checks`() {
        val db = TestDb(city = true)
        db.countries.add(mapOf("id" to 222, "iso" to "UA", "name_en" to "Ukraine"))
        db.cities["k"] = TestDb.CityDef(mapOf("id" to 3, "name_en" to "Kyiv"), null, 222)
        db.range("1.2.3.0", "1.2.3.255", Target.CityAt("k"))
        db.build()
        val end = db.countrySize + db.citySize
        // records starting 0..3 bytes before the end of the cities section: the country id (offset 3) does not fit
        for (back in 0..3) db.range("1.2.${4 + back}.0", "1.2.${4 + back}.255", Target.Raw(end - back))
        // here it fits (the rest of the record does not)
        db.range("1.2.8.0", "1.2.8.255", Target.Raw(end - 4))
        val geo = SxGeo(db.build())
        assertEquals(222, geo.countryId("1.2.3.4"))
        assertCorrupted { geo.lookup("1.2.8.4") }
        geo.countryId("1.2.8.4") // reads the record's last byte, whatever it is
        for (third in 4..7) {
            val ip = "1.2.$third.4"
            val expected = assertFailsWith<IllegalStateException> { geo.lookup(ip) }.message
            assertTrue(expected!!.startsWith("Corrupted Sypex Geo database: city record at offset "), expected)
            assertEquals(expected, assertFailsWith<IllegalStateException>(ip) { geo.country(ip) }.message)
            assertEquals(expected, assertFailsWith<IllegalStateException>(ip) { geo.countryId(ip) }.message)
        }
    }

    @Test
    fun `country fast path keeps the bounds checks for multi-byte ids`() {
        // 'S' country id at offset 6 and 'I' city country_id at offset 4: both end 8 bytes into the record
        val pack = listOf(
            "c6:code/S:id/c2:iso/n2:lat/n2:lon/b:name_ru/b:name_en",
            "S:country_seek/M:id/b:name_ru/b:name_en/b:iso",
            "I:region_seek/I:country_id/M:id/N5:lat/N5:lon/b:name_ru/b:name_en",
        )
        val db = TestDb(city = true, pack = pack)
        db.countries.add(mapOf("id" to 1234, "name_en" to "Big"))
        db.cities["k"] = TestDb.CityDef(mapOf("id" to 3, "name_en" to "Kyiv"), null, 70_000)
        db.range("1.2.0.0", "1.2.0.255", Target.CountryAt(1234))
        db.range("1.2.1.0", "1.2.1.255", Target.CityAt("k"))
        db.build()
        val sections = listOf("country" to db.countrySize, "city" to db.countrySize + db.citySize)
        // records starting 1..7 bytes before the end of their section: the id does not fit (it would with `+ size` dropped)
        for ((s, section) in sections.withIndex()) {
            for (back in 1..8) db.range("1.${3 + s}.$back.0", "1.${3 + s}.$back.255", Target.Raw(section.second - back))
        }
        val geo = SxGeo(db.build())
        assertEquals(1234, geo.countryId("1.2.0.4"))
        assertEquals(70_000, geo.countryId("1.2.1.4"))
        for ((s, section) in sections.withIndex()) {
            for (back in 1..8) {
                val ip = "1.${3 + s}.$back.4"
                val expected = assertFailsWith<IllegalStateException>(ip) { geo.lookup(ip) }.message
                assertTrue(expected!!.startsWith("Corrupted Sypex Geo database: ${section.first} record at offset "), expected)
                if (back == 8) {
                    geo.countryId(ip) // the id fits (the rest of the record does not): reads the record's last bytes
                    continue
                }
                assertEquals(expected, assertFailsWith<IllegalStateException>(ip) { geo.country(ip) }.message)
                assertEquals(expected, assertFailsWith<IllegalStateException>(ip) { geo.countryId(ip) }.message)
            }
        }
    }

    @Test
    fun `every main index granularity`() {
        // includes rangeLen values dividing the number of blocks, where the last index entry is the last chunk
        var lastChunkCovered = false
        for (seed in 10..11) {
            val db = TestDb.random(city = seed == 11, seed = seed, ranges = 120, sentinel = false)
            for (rangeLen in 1..24) {
                db.rangeLen = rangeLen
                val bytes = db.build()
                val n = db.starts.size
                val last = (1 until db.bIdxLen).last { db.bIdx[it] > db.bIdx[it - 1] }
                if (n % rangeLen == 0 && db.bIdx[last] - db.bIdx[last - 1] > rangeLen) lastChunkCovered = true
                verify(db, "seed $seed rangeLen $rangeLen", SxGeo(bytes))
            }
        }
        assertTrue(lastChunkCovered)
    }

    @Test
    fun `range starts resolve to their own range`() {
        val db = TestDb(city = false, rangeLen = 2, split = Split.NEVER)
        fun id(iso: String) = Target.Id(ISO_CODES.indexOf(iso))
        db.range("1.0.0.0", "1.0.0.255", id("RU"))
        db.range("1.0.1.0", "1.0.1.255", id("AU"))
        db.range("1.0.2.0", "1.0.2.255", id("CN"))
        db.range("1.0.3.0", "1.0.3.255", id("US"))
        db.range("1.0.4.0", "1.0.4.0", id("UA"))
        db.range("2.5.0.0", "3.255.255.255", id("RU")) // the only block of 2.x does not start at 2.0.0.0, 3.x has none
        db.range("4.0.0.0", "4.0.0.255", id("AU"))
        val geo = SxGeo(db.build())
        assertEquals("AU", geo.country("1.0.1.0"))
        assertEquals("CN", geo.country("1.0.2.0"))
        assertEquals("US", geo.country("1.0.3.0"))
        assertEquals("UA", geo.country("1.0.4.0"))
        assertNull(geo.country("1.0.4.1"))
        assertNull(geo.country("2.0.0.0")) // before the only block of 2.x: the gap block of 1.x, not the next block
        assertEquals("RU", geo.country("2.5.0.0"))
        assertEquals("RU", geo.country("2.5.1.0"))
        assertEquals("RU", geo.country("3.1.2.3")) // empty first-byte group: the block before it, not the next one
        assertEquals("AU", geo.country("4.0.0.0"))
        verify(db, "hand-made", geo, minFound = 20)
    }

    @Test
    fun `addresses before the first block are not found`() {
        val db = TestDb(city = false, split = Split.NEVER)
        db.fillGaps = false
        db.range("1.0.0.5", "1.0.0.9", Target.Id(7))
        db.range("3.0.1.0", "3.0.1.255", Target.Id(9))
        val geo = SxGeo(db.build())
        assertEquals(ip2long("1.0.0.5"), db.starts[0])
        assertNull(geo.countryId("1.0.0.4"))
        assertNull(geo.country("1.0.0.4"))
        assertNull(geo.lookup("1.0.0.4"))
        assertEquals(7, geo.countryId("1.0.0.5"))
        // blocks cover the address space up to the next block: 1.0.0.5 .. 3.0.0.255 is one block
        assertEquals(7, geo.countryId("2.0.0.0"))
        assertEquals(7, geo.countryId("3.0.0.255"))
        assertEquals(9, geo.countryId("3.0.1.0"))
        assertEquals(9, geo.countryId("223.255.255.255"))
    }

    @Test
    fun `fallback country is the full record`() {
        val db = TestDb.random(city = true, seed = 5, rangeLen = 16)
        val geo = SxGeo(db.build())
        fun ipOf(key: String) = long2ip(db.ranges.first { it.target == Target.CityAt(key) }.from)
        val noRegion = assertNotNull(geo.lookup(ipOf("c0")))
        assertNull(noRegion.region)
        assertEquals(db.expectedCountry(db.countries.first { it["id"] == db.cities.getValue("c0").countryId }), noRegion.country)
        assertTrue(noRegion.country.nameEn.isNotEmpty())

        val otherCountry = assertNotNull(geo.lookup(ipOf("c1")))
        assertEquals(db.cities.getValue("c1").countryId, otherCountry.country.id)
        assertTrue(otherCountry.country.nameEn.isNotEmpty() && otherCountry.country.fields.isNotEmpty())
        assertEquals(otherCountry.country.iso, geo.country(ipOf("c1")))

        val noRecord = assertNotNull(geo.lookup(ipOf("c2")))
        val id = db.cities.getValue("c2").countryId
        assertEquals(Country(id, ISO_CODES[id]), noRecord.country)
    }

    @Test
    fun `record fields and charsets`() {
        for (charset in 0..2) {
            val db = TestDb.random(city = true, seed = 20 + charset, ranges = 60, charset = charset, maxPack = true)
            val geo = SxGeo(db.build())
            assertEquals(listOf("UTF-8", "ISO-8859-1", "windows-1251")[charset], geo.info.charset.name())
            val ip = long2ip(db.ranges.first { db.expected(it.from).third?.region != null }.from)
            val info = assertNotNull(geo.lookup(ip))
            val city = assertNotNull(info.city)
            assertTrue(city.fields.keys.containsAll(listOf("id", "lat", "lon", "name_ru", "name_en", "f32", "alt", "u16", "u32", "temp")))
            assertTrue("region_seek" !in city.fields && "country_id" !in city.fields)
            assertTrue("country_seek" !in assertNotNull(info.region).fields)
            assertTrue(city.fields["f32"] is Float && city.fields["u32"] is Long && city.fields["alt"] is Double && city.fields["u16"] is Int)
            if (charset != 1) assertTrue(city.nameRu.first() in 'А'..'я', city.nameRu) else assertTrue(city.nameRu.any { it.code > 127 }, city.nameRu)
        }
    }

    @Test
    fun `c fields lose trailing spaces only`() {
        val db = TestDb(city = true, pack = MAX_PACK)
        db.countries.add(mapOf("id" to 222, "iso" to "UA", "name_en" to "Ukraine", "timezone" to " E/K  "))
        db.regions["r"] = TestDb.RegionDef(mapOf("id" to 1, "iso" to "UA-30", "okato" to " 1 2 \n  "), 222)
        db.regions["s"] = TestDb.RegionDef(mapOf("id" to 2, "iso" to "UA-31", "okato" to "      "), 222)
        db.cities["k"] = TestDb.CityDef(mapOf("id" to 3, "name_en" to "Kyiv"), "r", 222)
        db.cities["l"] = TestDb.CityDef(mapOf("id" to 4, "name_en" to "Lviv"), "s", 222)
        db.range("1.2.3.0", "1.2.3.255", Target.CityAt("k"))
        db.range("1.2.4.0", "1.2.4.255", Target.CityAt("l"))
        val geo = SxGeo(db.build())
        val info = assertNotNull(geo.lookup("1.2.3.4"))
        assertEquals(" 1 2 \n", info.region?.fields?.get("okato"))
        assertEquals(" E/K", info.country.fields["timezone"])
        assertEquals("", geo.lookup("1.2.4.4")?.region?.fields?.get("okato"))
        assertEquals("Kyiv", info.city?.nameEn)
    }

    private fun smallCountryDb(): SxGeo {
        val db = TestDb(city = false)
        db.range("1.2.3.0", "1.2.3.255", Target.Id(222))
        db.range("8.8.8.0", "8.8.8.255", Target.Id(225))
        db.range("100.0.0.0", "100.0.0.255", Target.Id(185))
        return SxGeo(db.build())
    }

    @Test
    fun `IPv4 parsing`() {
        val geo = smallCountryDb()
        for (ip in listOf("1.2.3.4", " 1.2.3.4", "1.2.3.4 ", "\t1.2.3.4\r\n", " \n 1.2.3.4\t ")) assertEquals("UA", geo.country(ip), ip)
        assertEquals("UA", geo.country("1.2.3.0"))
        assertEquals("RU", geo.country("100.0.0.0"))
        assertEquals("RU", geo.lookup("100.0.0.9")?.country?.iso)
        for (ip in listOf(
            "08.8.8.8", "8.8.8.08", "010.0.0.1", "1.2.3.04", "001.2.3.4", "1.02.3.4", "0100.0.0.0", "00.1.2.3",
            "\u00001.2.3.4", "1.2.3.4\u0000", "\u00A01.2.3.4", "1.2.3.4\u00A0", "\u20031.2.3.4", "\u30001.2.3.4", "\u000B1.2.3.4", "\u000C1.2.3.4",
            "1.2.3.\u0664", "\uFF11.2.3.4", "1.2.3", "1.2.3.4.5", "1..2.3", "1.2.3.", ".1.2.3", "1.2.3.256", "256.1.1.1", "1.2.3.1234",
            "-1.2.3.4", "+1.2.3.4", "1.2.3.+4", "1 .2.3.4", "1.2.3. 4", "a.b.c.d", "0x1.2.3.4", "::1", "", " ", null,
            "1.2.3.4, 10.0.0.1", "example.com", "1.2.3.4:80", "::ffff:1.2.3.4:80", "[::ffff:1.2.3.4]", "::ffff:", "::ffff:08.8.8.8",
            "::ffff: 1.2.3.4", " ::ffff:\t1.2.3.4", "::ffff:1.2.3", "::ffff::1.2.3.4", ":ffff:1.2.3.4", "::fff:1.2.3.4", "ffff:1.2.3.4",
            "0:0:0:0:0:ffff:1.2.3.4", "::ffff:0:1.2.3.4", "::1.2.3.4", "::ffff:0102:0304", "::ffff:1.2.3.4.5", "::\uFF46fff:1.2.3.4",
        )) {
            assertNull(geo.country(ip), ip)
            assertNull(geo.countryId(ip), ip)
            assertNull(geo.lookup(ip), ip)
        }
        for (ip in listOf("0.0.0.0", "10.1.2.3", "127.0.0.1", "255.255.255.255", "224.0.0.1", "::ffff:127.0.0.1")) assertNull(geo.lookup(ip), ip)
        // IPv4-mapped IPv6, the prefix in any case
        for (ip in listOf("::ffff:1.2.3.4", "::FFFF:1.2.3.4", "::fFfF:1.2.3.4", " ::ffff:1.2.3.4\r\n", "\t::Ffff:1.2.3.0")) {
            assertEquals("UA", geo.country(ip), ip)
            assertEquals(222, geo.countryId(ip), ip)
            assertEquals(geo.lookup("1.2.3.4"), geo.lookup(ip), ip)
        }
        assertEquals("US", geo.country("::ffff:8.8.8.8"))
    }

    @Test
    fun `InetAddress overloads`() {
        val geo = smallCountryDb()
        val v4 = InetAddress.getByAddress(byteArrayOf(1, 2, 3, 4)) as Inet4Address
        val asInet: InetAddress = v4
        assertEquals("UA", geo.country(v4))
        assertEquals("UA", geo.country(asInet))
        assertEquals(222, geo.countryId(v4))
        assertEquals(222, geo.countryId(asInet))
        assertEquals(geo.lookup("1.2.3.4"), geo.lookup(v4))
        assertEquals(geo.lookup("1.2.3.4"), geo.lookup(asInet))
        assertEquals("RU", geo.country(InetAddress.getByAddress(byteArrayOf(100, 0, 0, 0))))
        // high octets are unsigned
        assertNull(geo.country(InetAddress.getByAddress(byteArrayOf(-1, -1, -1, -1))))
        assertNull(geo.lookup(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))))
        // the JDK turns IPv4-mapped IPv6 into an Inet4Address
        val mapped = InetAddress.getByAddress(ByteArray(16).also { it[10] = -1; it[11] = -1; it[12] = 1; it[13] = 2; it[14] = 3; it[15] = 4 })
        assertTrue(mapped is Inet4Address)
        assertEquals("UA", geo.country(mapped))
        assertEquals("UA", geo.country(InetAddress.getByName("::ffff:1.2.3.4")))
        // real IPv6, including the IPv4-compatible ::1.2.3.4: not found
        for (v6 in listOf(InetAddress.getByName("2001:db8::1"), InetAddress.getByName("::1.2.3.4"), InetAddress.getByName("::1"),
            Inet6Address.getByAddress(null, ByteArray(16).also { it[10] = -1; it[11] = -1; it[12] = 1; it[13] = 2; it[14] = 3; it[15] = 4 }, null))) {
            assertTrue(v6 is Inet6Address, v6.toString())
            assertNull(geo.lookup(v6), v6.toString())
            assertNull(geo.country(v6), v6.toString())
            assertNull(geo.countryId(v6), v6.toString())
        }
    }

    @Test
    fun `localized names`() {
        val ru = Locale("ru")
        val locales = listOf(Locale.ENGLISH, Locale.GERMAN, Locale.ROOT, Locale.forLanguageTag("uk-UA"))
        val country = Country(185, "RU", "Россия", "Russia")
        val region = Region(1, "RU-MOW", "Москва", "Moscow", emptyMap())
        val city = City(2, "Москва", "Moscow", 55.75, 37.62, emptyMap())
        for (l in listOf(ru, Locale.forLanguageTag("ru-RU"), Locale("RU", "UA"))) {
            assertEquals("Россия", country.name(l))
            assertEquals("Москва", region.name(l))
            assertEquals("Москва", city.name(l))
        }
        for (l in locales) {
            assertEquals("Russia", country.name(l))
            assertEquals("Moscow", region.name(l))
            assertEquals("Moscow", city.name(l))
        }
        // a missing name falls back to the other language
        assertEquals("Russia", country.copy(nameRu = "").name(ru))
        assertEquals("Россия", country.copy(nameEn = "").name(Locale.ENGLISH))
        assertEquals("Moscow", region.copy(nameRu = "").name(ru))
        assertEquals("Москва", city.copy(nameEn = "").name(Locale.GERMAN))
        assertEquals("", Country(185, "RU").name(ru))
        assertEquals("", Country(185, "RU").name(Locale.ENGLISH))
    }

    @Test
    fun `input stream constructor reads to the end and does not close the stream`() {
        val bytes = TestDb.random(city = true, seed = 5).build()
        var closed = false
        // returns at most 1000 bytes per read call
        val stream = object : java.io.ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(len, 1000))
            override fun close() { closed = true }
        }
        val geo = SxGeo(stream)
        assertTrue(!closed)
        assertEquals(-1, stream.read())
        val reference = SxGeo(bytes)
        val ips = List(2000) { long2ip((it.toLong() * 2_147_483_647L) % 0xE0000000L) }
        assertEquals(ips.map(reference::lookup), ips.map(geo::lookup))
        val failing = object : InputStream() { override fun read(): Int = throw java.io.IOException("boom") }
        assertEquals("boom", assertFailsWith<java.io.IOException> { SxGeo(failing) }.message)
        // no file name to prefix the message with
        assertEquals("Not a Sypex Geo database: file is empty or too short (0 bytes)",
            assertFailsWith<IllegalArgumentException> { SxGeo(java.io.ByteArrayInputStream(ByteArray(0))) }.message)
    }

    @Test
    fun `constructor errors say what the data looks like`() {
        val prefix = "Not a Sypex Geo database: "
        val zip = "this is a ZIP archive - unzip it and pass the .dat file"
        val html = "looks like an HTML page - the download probably failed"
        val mmdb = "this is a MaxMind .mmdb file"
        fun message(data: ByteArray) = assertFailsWith<IllegalArgumentException> { SxGeo(data) }.message
        fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)
        val marker = byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte()) + bytes("MaxMind.com")
        val random = kotlin.random.Random(1)
        assertEquals(prefix + zip, message(byteArrayOf(0x50, 0x4B, 3, 4) + random.nextBytes(5000)))
        assertEquals(prefix + zip, message(byteArrayOf(0x50, 0x4B, 3, 4)))
        for (page in listOf("<!DOCTYPE html><html><body>Not found</body></html>", "<html>", "<", "\uFEFF<!doctype html>",
            " \r\n\t<?xml version=\"1.0\"?>", "\uFEFF\n  <HTML>" + "x".repeat(100))) {
            assertEquals(prefix + html, message(bytes(page)), page)
        }
        assertEquals(prefix + mmdb, message(random.nextBytes(300_000) + marker + random.nextBytes(20_000)))
        assertEquals(prefix + mmdb, message(marker))
        assertEquals("Not a Sypex Geo database", message(marker + ByteArray(128 * 1024))) // too far from the end
        // the marker is looked for in the last 128 KiB only
        val mmdbTail = ByteArray(200_000)
        marker.copyInto(mmdbTail, mmdbTail.size - 128 * 1024)
        assertEquals(prefix + mmdb, message(mmdbTail))
        ByteArray(marker.size).copyInto(mmdbTail, mmdbTail.size - 128 * 1024)
        marker.copyInto(mmdbTail, mmdbTail.size - 128 * 1024 - 1) // starts before the last 128 KiB
        assertEquals("Not a Sypex Geo database", message(mmdbTail))
        assertEquals(prefix + "file is empty or too short (0 bytes)", message(ByteArray(0)))
        assertEquals(prefix + "file is empty or too short (3 bytes)", message(bytes("SxG")))
        assertEquals(prefix + "file is empty or too short (39 bytes)", message(TestDb.random(city = false, seed = 1).build().copyOf(39)))
        assertEquals("Not a Sypex Geo database", message(ByteArray(100)))
        assertEquals("Not a Sypex Geo database", message(bytes("PK\u0005\u0006" + "x".repeat(100)))) // not a local file header
        assertEquals("Not a Sypex Geo database", message(bytes("x<html>" + "x".repeat(100))))

        // the Path and File constructors start the message with the file name
        val file = Files.createTempFile("sxgeo", ".zip")
        try {
            Files.write(file, byteArrayOf(0x50, 0x4B, 3, 4) + ByteArray(100))
            // the original exception is kept as the cause
            for (e in listOf(assertFailsWith<IllegalArgumentException> { SxGeo(file) },
                assertFailsWith<IllegalArgumentException> { SxGeo(file.toFile()) })) {
                assertEquals("$file: $prefix$zip", e.message)
                assertEquals<Class<*>?>(IllegalArgumentException::class.java, e.cause?.javaClass)
                assertEquals(prefix + zip, e.cause?.message)
            }
            Files.write(file, TestDb.random(city = false, seed = 1).build().let { it.copyOf(it.size - 1) })
            assertEquals("$file: Truncated Sypex Geo database", assertFailsWith<IllegalArgumentException> { SxGeo(file) }.message)
            assertEquals("$file: Truncated Sypex Geo database", assertFailsWith<IllegalArgumentException> { SxGeo(file.toFile()) }.message)
            Files.write(file, ByteArray(0))
            assertEquals("$file: ${prefix}file is empty or too short (0 bytes)", assertFailsWith<IllegalArgumentException> { SxGeo(file) }.message)
        } finally {
            Files.delete(file)
        }
    }

    @Test
    fun `database info`() {
        val country = TestDb.random(city = false, seed = 1)
        val info = SxGeo(country.build()).info
        assertEquals(DbInfo("2.2", Instant.ofEpochSecond(1_700_000_000), "SxGeo Country", Charsets.ISO_8859_1, false, country.starts.size), info)
        val city = TestDb.random(city = true, seed = 7, charset = 2, maxPack = true)
        val cityInfo = SxGeo(city.build()).info
        assertEquals("SxGeo City Max", cityInfo.type)
        assertEquals(Charset.forName("windows-1251"), cityInfo.charset)
        assertTrue(cityInfo.hasCities)
    }

    @Test
    fun `byte array is copied`() {
        val bytes = TestDb.random(city = true, seed = 5).build()
        val geo = SxGeo(bytes)
        val ips = List(2000) { long2ip((it.toLong() * 2_147_483_647L) % 0xE0000000L) }
        val before = ips.map(geo::lookup)
        bytes.fill(0)
        assertEquals(before, ips.map(geo::lookup))
        assertTrue(before.count { it != null } > 100)
    }

    @Test
    fun `file and path constructors`() {
        val db = TestDb.random(city = true, seed = 5)
        val file = Files.createTempFile("sxgeo", ".dat")
        try {
            Files.write(file, db.build())
            val ip = long2ip(db.ranges[10].from)
            assertEquals(SxGeo(db.build()).lookup(ip), SxGeo(file).lookup(ip))
            assertEquals(SxGeo(file).lookup(ip), SxGeo(file.toFile()).lookup(ip))
        } finally {
            Files.delete(file)
        }
        assertFailsWith<java.io.IOException> { SxGeo(file) }
        assertFailsWith<java.io.IOException> { SxGeo(file.toFile()) }
    }

    @Test
    fun `rejects non Sypex Geo data`() {
        assertFailsWith<IllegalArgumentException> { SxGeo(ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { SxGeo(ByteArray(100)) }
        assertFailsWith<IllegalArgumentException> { SxGeo("SxG".toByteArray()) }
        val good = TestDb.random(city = false, seed = 1).build()
        assertFailsWith<IllegalArgumentException> { SxGeo(good.copyOf().also { it[3] = 21 }) } // version 2.1
        assertFailsWith<IllegalArgumentException> { SxGeo(good.copyOf().also { it[9] = 3 }) } // charset
        assertFailsWith<IllegalArgumentException> { SxGeo(good.copyOf().also { it[19] = 0 }) } // id_len
        // id_len 5 with enough padding that the file is not truncated: only the header check can reject it
        val dbItems = ByteBuffer.wrap(good, 15, 4).int
        val idLen5 = good.copyOf(good.size + dbItems * 4).also { it[19] = 5 }
        assertEquals("Corrupted Sypex Geo database header", assertFailsWith<IllegalArgumentException> { SxGeo(idLen5) }.message)
        assertFailsWith<IllegalArgumentException> { SxGeo(good.copyOf().also { it[13] = 0; it[14] = 0 }) } // range_len
    }

    @Test
    fun `rejects truncated databases`() {
        val city = TestDb.random(city = true, seed = 5)
        val full = city.build()
        SxGeo(full)
        // country records are not counted in the header's city_size: every cut must still be detected
        for (cut in (1..city.countrySize + 64) + listOf(city.citySize, full.size / 2, full.size - 40, full.size)) {
            assertFailsWith<IllegalArgumentException>("cut $cut") { SxGeo(full.copyOf(full.size - cut)) }
        }
        val country = TestDb.random(city = false, seed = 1).build()
        for (cut in 1..20) assertFailsWith<IllegalArgumentException>("cut $cut") { SxGeo(country.copyOf(country.size - cut)) }
    }

    @Test
    fun `validates the first-byte index`() {
        val db = TestDb.random(city = false, seed = 1)
        val bytes = db.build()
        SxGeo(bytes)
        fun withEntry(i: Int, value: Long) = bytes.copyOf().also { b -> for (k in 0..3) b[40 + i * 4 + k] = (value ushr (24 - 8 * k)).toByte() }
        assertFailsWith<IllegalArgumentException> { SxGeo(withEntry(5, db.starts.size + 1L)) }
        assertFailsWith<IllegalArgumentException> { SxGeo(withEntry(50, db.bIdx[49] - 1L)) }
        assertFailsWith<IllegalArgumentException> { SxGeo(withEntry(223, 0xFFFFFFFFL)) }
    }

    @Test
    fun `validates pack formats`() {
        val db = TestDb.random(city = true, seed = 5)
        val ip = long2ip(db.ranges.first { it.target is Target.CityAt }.from)
        val reference = SxGeo(db.build()).lookup(ip)
        fun withPack(pack: String): SxGeo = SxGeo(db.apply { packOverride = pack }.build())
        // empty tokens (a trailing '/' or "//") are ignored
        assertEquals(reference, withPack(CITY_PACK.joinToString("\u0000") { "$it/" }).lookup(ip))
        assertEquals(reference, withPack(CITY_PACK.joinToString("\u0000") { it.replace("/", "//") }).lookup(ip))
        for (bad in listOf("c:iso", "x2:iso", "n-2:lat", "c-5:iso", "T5:id", "b0:name", "T", "Tid", ":id", "c2iso", "n2.5:lat", "c99999999999:iso")) {
            assertFailsWith<IllegalArgumentException>(bad) { withPack(listOf("T:id/$bad", CITY_PACK[1], CITY_PACK[2]).joinToString("\u0000")) }
        }
        assertFailsWith<IllegalArgumentException> { withPack(CITY_PACK.take(2).joinToString("\u0000")) }
        assertFailsWith<IllegalArgumentException> { withPack("") }

        // 'n' without digits means 0 decimals
        val n0 = TestDb(city = true, pack = listOf("T:id/c2:iso/n:lat/n:lon/b:name_ru/b:name_en", CITY_PACK[1], CITY_PACK[2]))
        n0.countries.add(mapOf("id" to 185, "iso" to "RU", "lat" to 56.0, "lon" to -38.0))
        n0.cities["c"] = TestDb.CityDef(mapOf("id" to 1, "name_en" to "Moscow"), null, 185)
        n0.range("1.2.3.0", "1.2.3.255", Target.CountryAt(185))
        n0.range("1.2.4.0", "1.2.4.255", Target.CityAt("c"))
        val country = assertNotNull(SxGeo(n0.build()).lookup("1.2.3.4")).country
        assertEquals(56.0, country.lat)
        assertEquals(-38.0, country.lon)
        assertEquals(country, SxGeo(n0.build()).lookup("1.2.4.4")?.country)
    }

    private fun assertCorrupted(block: () -> Any?) {
        val e = assertFailsWith<IllegalStateException> { block() }
        assertTrue(e.message!!.startsWith("Corrupted Sypex Geo database"), e.message)
    }

    @Test
    fun `corrupted records throw IllegalStateException`() {
        val db = TestDb(city = true)
        db.countries.add(mapOf("id" to 222, "iso" to "UA", "name_en" to "Ukraine"))
        db.regions["r"] = TestDb.RegionDef(mapOf("id" to 1, "iso" to "UA-30"), 222)
        db.regions["bad"] = TestDb.RegionDef(mapOf("id" to 2, "iso" to "UA-31"), 222, countrySeek = 60000)
        db.cities["k"] = TestDb.CityDef(mapOf("id" to 3, "name_en" to "Kyiv"), "r", 222)
        db.cities["far"] = TestDb.CityDef(mapOf("id" to 4, "name_en" to "Far"), null, 222, regionSeek = 1 shl 20)
        db.cities["bad"] = TestDb.CityDef(mapOf("id" to 5, "name_en" to "Bad"), "bad", 222)
        // offsets that stay inside the file but leave their section; placeholders until the seeks are known
        db.regions["intoCities"] = TestDb.RegionDef(mapOf("id" to 7, "iso" to "UA-32"), 222, countrySeek = 1)
        db.cities["viaCity"] = TestDb.CityDef(mapOf("id" to 8, "name_en" to "ViaCity"), "intoCities", 222)
        db.cities["inside"] = TestDb.CityDef(mapOf("id" to 9, "name_en" to "Inside"), null, 222, regionSeek = 1)
        db.cities["last"] = TestDb.CityDef(mapOf("id" to 6, "name_en" to "Last"), "r", 222)
        db.range("1.2.3.0", "1.2.3.255", Target.CityAt("k"))
        db.range("1.2.4.0", "1.2.4.255", Target.Raw(5_000_000)) // city offset past the end of the file
        db.range("1.2.5.0", "1.2.5.255", Target.CityAt("far"))
        db.range("1.2.6.0", "1.2.6.255", Target.CityAt("bad"))
        db.range("1.2.7.0", "1.2.7.255", Target.CityAt("last"))
        db.range("1.2.8.0", "1.2.8.255", Target.CityAt("viaCity"))
        db.range("1.2.9.0", "1.2.9.255", Target.CityAt("inside"))
        db.build()
        val kyivSeek = db.citySeek.getValue("k")
        val regionSize = db.regionSize
        // a region whose country offset is a city record (in the cities section, past the country records)
        db.regions["intoCities"] = TestDb.RegionDef(mapOf("id" to 7, "iso" to "UA-32"), 222, countrySeek = kyivSeek)
        // a city whose region offset is just past the regions section, on Kyiv's record: decoded as a region,
        // it would point to the UA country record and not fail by itself
        db.cities["inside"] = TestDb.CityDef(mapOf("id" to 9, "name_en" to "Inside"), null, 222, regionSeek = regionSize + kyivSeek)
        val bytes = db.build()
        assertEquals(kyivSeek, db.citySeek.getValue("k"))
        assertEquals(regionSize, db.regionSize)
        assertTrue(kyivSeek >= db.countrySize)
        assertTrue(regionSize + kyivSeek < regionSize + db.countrySize + db.citySize)
        val geo = SxGeo(bytes)
        assertEquals("Kyiv", geo.lookup("1.2.3.4")?.city?.nameEn)
        assertEquals("Last", geo.lookup("1.2.7.4")?.city?.nameEn)
        assertCorrupted { geo.lookup("1.2.4.4") }
        assertCorrupted { geo.country("1.2.4.4") }
        assertCorrupted { geo.countryId("1.2.4.4") }
        assertCorrupted { geo.lookup("1.2.5.4") } // region offset outside the regions section
        assertEquals("UA", geo.country("1.2.5.4"))
        assertCorrupted { geo.lookup("1.2.6.4") } // country offset outside the country records
        assertEquals("UA", geo.country("1.2.6.4"))
        assertCorrupted { geo.lookup("1.2.8.4") } // country offset inside the cities section
        assertEquals("UA", geo.country("1.2.8.4"))
        assertEquals(222, geo.countryId("1.2.8.4"))
        assertCorrupted { geo.lookup("1.2.9.4") } // region offset inside the file, past the regions section
        assertEquals("UA", geo.country("1.2.9.4"))
        assertEquals(222, geo.countryId("1.2.9.4"))

        // the last string of the last city record loses its terminating NUL
        val noNul = SxGeo(bytes.copyOf().also { it[it.size - 1] = 'x'.code.toByte() })
        assertCorrupted { noNul.lookup("1.2.7.4") }
        assertEquals("Kyiv", noNul.lookup("1.2.3.4")?.city?.nameEn)

        // a fixed-size field running past the end of the cities section
        db.packOverride = listOf(CITY_PACK[0], CITY_PACK[1], CITY_PACK[2].replace("b:name_en", "c200:name_en")).joinToString("\u0000")
        val longField = SxGeo(db.build())
        assertCorrupted { longField.lookup("1.2.7.4") }

        // broken country records are found by the constructor
        db.packOverride = listOf(CITY_PACK[0].replace("b:name_en", "c100000:name_en"), CITY_PACK[1], CITY_PACK[2]).joinToString("\u0000")
        assertEquals("Corrupted Sypex Geo database: invalid country records",
            assertFailsWith<IllegalArgumentException> { SxGeo(db.build()) }.message)
    }

    @Test
    fun `corruption messages are the same as in the Java port`() {
        val db = TestDb(city = true)
        db.countries.add(mapOf("id" to 222, "iso" to "UA", "name_en" to "Ukraine"))
        db.cities["k"] = TestDb.CityDef(mapOf("id" to 3, "name_en" to "Kyiv"), null, 222)
        db.range("1.2.3.0", "1.2.3.255", Target.CityAt("k"))
        db.range("1.2.4.0", "1.2.4.255", Target.Raw(5_000_000))
        val geo = SxGeo(db.build())
        assertEquals("Corrupted Sypex Geo database: city record at offset 5000000 is out of bounds",
            assertFailsWith<IllegalStateException> { geo.lookup("1.2.4.4") }.message)
        db.packOverride = listOf(CITY_PACK[0], CITY_PACK[1], CITY_PACK[2] + "/X:x").joinToString("\u0000")
        assertEquals("Invalid pack format 'X:x' in Sypex Geo database",
            assertFailsWith<IllegalArgumentException> { SxGeo(db.build()) }.message)
    }

    @Test
    fun `a negative block id is corrupted in a city database and unknown in a country database`() {
        // a 4-byte block id with the high bit set is a negative record offset (city) or country id (country)
        for (raw in listOf(0x80000001.toInt(), 0x80000000.toInt(), -1)) {
            val db = TestDb(city = true, idLen = 4)
            db.countries.add(mapOf("id" to 222, "iso" to "UA", "name_en" to "Ukraine"))
            db.cities["k"] = TestDb.CityDef(mapOf("id" to 3, "name_en" to "Kyiv"), null, 222)
            db.range("1.2.3.0", "1.2.3.255", Target.CityAt("k"))
            db.range("1.2.4.0", "1.2.4.255", Target.Raw(raw))
            val geo = SxGeo(db.build())
            assertEquals("Kyiv", geo.lookup("1.2.3.4")?.city?.nameEn)
            val message = "Corrupted Sypex Geo database: country record at offset $raw is out of bounds"
            assertEquals(message, assertFailsWith<IllegalStateException> { geo.lookup("1.2.4.4") }.message)
            assertEquals(message, assertFailsWith<IllegalStateException> { geo.country("1.2.4.4") }.message)
            assertEquals(message, assertFailsWith<IllegalStateException> { geo.countryId("1.2.4.4") }.message)

            val country = TestDb(city = false, idLen = 4)
            country.range("1.2.3.0", "1.2.3.255", Target.Id(222))
            country.range("1.2.4.0", "1.2.4.255", Target.Raw(raw))
            val cgeo = SxGeo(country.build())
            assertEquals("UA", cgeo.country("1.2.3.4"))
            assertNull(cgeo.lookup("1.2.4.4"))
            assertNull(cgeo.country("1.2.4.4"))
            assertNull(cgeo.countryId("1.2.4.4"))
        }
    }

    @Test
    fun `random addresses never throw`() {
        val dbs = listOf(TestDb.random(city = false, seed = 3, split = Split.NEVER), TestDb.random(city = true, seed = 8, split = Split.NEVER))
        for (db in dbs) {
            val geo = SxGeo(db.build())
            val random = kotlin.random.Random(42)
            repeat(20_000) {
                val ip = random.nextLong(0, 1L shl 32)
                val (id, iso, info) = db.expected(ip)
                val s = long2ip(ip)
                assertEquals(id, geo.countryId(s), s)
                assertEquals(iso, geo.country(s), s)
                assertEquals(info, geo.lookup(s), s)
            }
        }
    }
}
