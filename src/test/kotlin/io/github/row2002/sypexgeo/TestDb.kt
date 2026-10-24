package io.github.row2002.sypexgeo

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import kotlin.random.Random

/** Sypex Geo country id -> ISO code (independent copy of the table in SxGeo.php). */
val ISO_CODES: List<String> = (
    "--APEUADAEAFAGAIALAMCWAOAQARASATAUAWAZBABBBDBEBFBGBHBIBJBMBNBOBRBSBTBVBWBYBZCACCCDCFCGCHCICKCLCMCNCOCRCUCVCXCYCZ" +
        "DEDJDKDMDODZECEEEGEHERESETFIFJFKFMFOFRSXGAGBGDGEGFGHGIGLGMGNGPGQGRGSGTGUGWGYHKHMHNHRHTHUIDIEILINIOIQIRISITJMJOJPKE" +
        "KGKHKIKMKNKPKRKWKYKZLALBLCLILKLRLSLTLULVLYMAMCMDMGMHMKMLMMMNMOMPMQMRMSMTMUMVMWMXMYMZNANCNENFNGNINLNONPNRNUNZOMPAPE" +
        "PFPGPHPKPLPMPNPRPSPTPWPYQARERORURWSASBSCSDSESGSHSISJSKSLSMSNSOSRSTSVSYSZTCTDTFTGTHTJTKTMTNTOTLTRTTTVTWTZUAUGUMUSUY" +
        "UZVAVCVEVGVIVNVUWFWSYEYTRSZAZMMEZWA1XKO1AXGGIMJEBLMFBQSS"
    ).chunked(2).map { if (it == "--") "" else it }

/** Pack formats of the real SxGeoCity.dat. */
val CITY_PACK = listOf(
    "T:id/c2:iso/n2:lat/n2:lon/b:name_ru/b:name_en",
    "S:country_seek/M:id/b:name_ru/b:name_en/b:iso",
    "M:region_seek/T:country_id/M:id/N5:lat/N5:lon/b:name_ru/b:name_en",
)

/** Synthetic "Max-like" formats using every pack code. */
val MAX_PACK = listOf(
    "T:id/c2:iso/n2:lat/n2:lon/b:name_ru/b:name_en/c6:timezone/I:population/t:t8/s:s16/d:area",
    "S:country_seek/M:id/b:name_ru/b:name_en/b:iso/c10:okato/m:m24/i:i32",
    "M:region_seek/T:country_id/M:id/N5:lat/N5:lon/b:name_ru/b:name_en/f:f32/n1:alt/S:u16/I:u32/n:temp",
)

fun ip2long(ip: String): Long = ip.split('.').fold(0L) { acc, p -> (acc shl 8) or p.toLong() }

fun long2ip(ip: Long): String = "${ip ushr 24 and 255}.${ip ushr 16 and 255}.${ip ushr 8 and 255}.${ip and 255}"

/** How blocks are split at first-byte boundaries (the real files use [ALWAYS] for cities, [EMPTY_ONLY] for countries). */
enum class Split { ALWAYS, EMPTY_ONLY, NEVER }

/** What an IP range points to. */
sealed class Target {
    /** Country database: country id. */
    data class Id(val id: Int) : Target()
    /** City database: a city record. */
    data class CityAt(val key: String) : Target()
    /** City database: a country record (country-level range). */
    data class CountryAt(val id: Int) : Target()
    /** City database: a raw record offset (corruption tests). */
    data class Raw(val seek: Int) : Target()
}

/**
 * Test-side writer of Sypex Geo 2.2 files (a port of the review harness mkdb.py) with a brute-force reference.
 * Layout: 40-byte big-endian header, pack formats, b_idx, m_idx (full start IP of every rangeLen-th block),
 * blocks (3 low bytes of the start IP + id), regions (pad byte + records), cities (pad byte + country records + city records).
 */
class TestDb(
    val city: Boolean,
    var rangeLen: Int = 16,
    val idLen: Int = if (city) 3 else 1,
    val charset: Int = if (city) 0 else 1,
    val split: Split = if (city) Split.ALWAYS else Split.EMPTY_ONLY,
    val sentinel: Boolean = city,
    val pack: List<String> = CITY_PACK,
    val type: Int = if (city) 4 else 1,
    val bIdxLen: Int = 224,
) {
    class RegionDef(val values: Map<String, Any>, val countryId: Int, val countrySeek: Int? = null)
    class CityDef(val values: Map<String, Any>, val region: String?, val countryId: Int, val regionSeek: Int? = null)
    class Range(val from: Long, val to: Long, val target: Target)

    val countries = mutableListOf<Map<String, Any>>()
    val regions = LinkedHashMap<String, RegionDef>()
    val cities = LinkedHashMap<String, CityDef>()
    val ranges = mutableListOf<Range>()
    /** Written to the header instead of the real pack formats (records are still encoded with [pack]). */
    var packOverride: String? = null
    /** `false`: no "not found" blocks for the gaps, a block covers everything up to the next one ([expected] does not model this). */
    var fillGaps = true

    // filled by build()
    var starts = LongArray(0)
    var ids = IntArray(0)
    var bIdx = IntArray(0)
    var mIdx = LongArray(0)
    var countrySize = 0
    var regionSize = 0
    var citySize = 0
    val countrySeek = HashMap<Int, Int>()
    val citySeek = HashMap<String, Int>()

    private val cs: Charset = listOf(Charsets.UTF_8, Charsets.ISO_8859_1, Charset.forName("windows-1251"))[charset]

    fun range(from: String, to: String, target: Target) = ranges.add(Range(ip2long(from), ip2long(to), target))

    fun build(): ByteArray {
        val packs = pack.map(::fields)
        var regionBytes = ByteArray(0)
        var cityBytes = ByteArray(0)
        var maxCountry = 0
        var maxRegion = 0
        var maxCity = 0
        countrySeek.clear()
        citySeek.clear()
        if (city) {
            val cities = ByteArrayOutputStream().apply { write(0) }
            for (c in countries) {
                countrySeek.putIfAbsent((c["id"] as Number).toInt(), cities.size())
                cities.write(encode(packs[0], c).also { maxCountry = maxOf(maxCountry, it.size) })
            }
            countrySize = cities.size()
            val regionsOut = ByteArrayOutputStream().apply { write(0) }
            val regionSeek = HashMap<String, Int>()
            for ((key, r) in regions) {
                regionSeek[key] = regionsOut.size()
                val seek = r.countrySeek ?: countrySeek[r.countryId] ?: 0
                regionsOut.write(encode(packs[1], r.values + ("country_seek" to seek)).also { maxRegion = maxOf(maxRegion, it.size) })
            }
            for ((key, c) in this.cities) {
                citySeek[key] = cities.size()
                val seek = c.regionSeek ?: c.region?.let { regionSeek.getValue(it) } ?: 0
                cities.write(encode(packs[2], c.values + mapOf("region_seek" to seek, "country_id" to c.countryId))
                    .also { maxCity = maxOf(maxCity, it.size) })
            }
            citySize = cities.size() - countrySize // like the real files: city_size excludes the country records
            regionBytes = regionsOut.toByteArray()
            regionSize = regionBytes.size
            cityBytes = cities.toByteArray()
        }
        blocks()
        val packBytes = if (city) (packOverride ?: pack.joinToString("\u0000")).toByteArray(Charsets.ISO_8859_1) else ByteArray(0)
        val out = Out()
        out.raw("SxG".toByteArray())
        out.be(22, 1); out.be(1_700_000_000, 4); out.be(type.toLong(), 1); out.be(charset.toLong(), 1)
        out.be(bIdxLen.toLong(), 1); out.be(mIdx.size.toLong(), 2); out.be(rangeLen.toLong(), 2); out.be(starts.size.toLong(), 4)
        out.be(idLen.toLong(), 1); out.be(maxRegion.toLong(), 2); out.be(maxCity.toLong(), 2)
        out.be(regionBytes.size.toLong(), 4); out.be(citySize.toLong(), 4); out.be(maxCountry.toLong(), 2)
        out.be(countrySize.toLong(), 4); out.be(packBytes.size.toLong(), 2)
        out.raw(packBytes)
        bIdx.forEach { out.be(it.toLong(), 4) }
        mIdx.forEach { out.be(it, 4) }
        for (i in starts.indices) { out.be(starts[i] and 0xFFFFFF, 3); out.be(ids[i].toLong(), idLen) }
        out.raw(regionBytes)
        out.raw(cityBytes)
        return out.toByteArray()
    }

    private fun idOf(t: Target): Int = when (t) {
        is Target.Id -> t.id
        is Target.CityAt -> citySeek.getValue(t.key)
        is Target.CountryAt -> countrySeek.getValue(t.id)
        is Target.Raw -> t.seek
    }

    private fun blocks() {
        val lo = 1L shl 24
        val hi = (bIdxLen.toLong() shl 24) - 1
        val sorted = ranges.sortedBy { it.from }
        sorted.zipWithNext { a, b -> require(a.to < b.from) { "overlapping ranges ${long2ip(b.from)}" } }
        val segs = ArrayList<Triple<Long, Long, Int>>()
        var pos = lo
        for (r in sorted) {
            val a = maxOf(r.from, lo)
            val b = minOf(r.to, hi)
            if (a > b) continue
            if (a > pos && fillGaps) segs.add(Triple(pos, a - 1, 0))
            segs.add(Triple(a, b, idOf(r.target)))
            pos = b + 1
        }
        if (pos <= hi && fillGaps) segs.add(Triple(pos, hi, 0))
        val merged = ArrayList<Triple<Long, Long, Int>>()
        for (s in segs) {
            val last = merged.lastOrNull()
            if (last != null && last.third == s.third && last.second + 1 == s.first) merged[merged.size - 1] = Triple(last.first, s.second, s.third)
            else merged.add(s)
        }
        val st = ArrayList<Long>()
        val id = ArrayList<Int>()
        for ((a, b, i) in merged) {
            if (split == Split.ALWAYS) {
                var cur = a
                while (cur <= b) { st.add(cur); id.add(i); cur = ((cur ushr 24) + 1) shl 24 }
            } else {
                st.add(a); id.add(i)
            }
        }
        if (split == Split.EMPTY_ONLY) { // an extra block at X.0.0.0 for every first byte that would have no block
            val s2 = ArrayList<Long>()
            val i2 = ArrayList<Int>()
            for (k in st.indices) {
                s2.add(st[k]); i2.add(id[k])
                val next = if (k + 1 < st.size) st[k + 1] else hi + 1
                for (o in (st[k] ushr 24) + 1 until minOf(next ushr 24, (hi ushr 24) + 1)) { s2.add(o shl 24); i2.add(id[k]) }
            }
            st.clear(); st.addAll(s2); id.clear(); id.addAll(i2)
        }
        starts = st.toLongArray()
        ids = id.toIntArray()
        val n = starts.size
        bIdx = IntArray(bIdxLen) { f -> starts.count { (it ushr 24) <= f } }
        val m = (0 until (n - 1) / rangeLen).map { starts[(it + 1) * rangeLen] }.toMutableList()
        if (sentinel || m.isEmpty()) m.add(0xFFFFFFFFL)
        mIdx = m.toLongArray()
    }

    private class Field(val code: Char, val digits: Int, val name: String)

    private fun fields(format: String) = format.split('/').filter { it.isNotEmpty() }.map {
        Field(it[0], it.substring(1, it.indexOf(':')).toIntOrNull() ?: 0, it.substringAfter(':'))
    }

    private fun encode(fields: List<Field>, values: Map<String, Any>): ByteArray {
        val out = Out()
        for (f in fields) {
            val v = values[f.name] ?: if (f.code == 'c' || f.code == 'b') "" else 0
            when (f.code) {
                't', 'T' -> out.le((v as Number).toLong(), 1)
                's', 'S' -> out.le((v as Number).toLong(), 2)
                'm', 'M' -> out.le((v as Number).toLong(), 3)
                'i', 'I' -> out.le((v as Number).toLong(), 4)
                'f' -> out.le((v as Number).toFloat().toRawBits().toLong(), 4)
                'd' -> out.le((v as Number).toDouble().toRawBits(), 8)
                'n' -> out.le(Math.round((v as Number).toDouble() * Math.pow(10.0, f.digits.toDouble())), 2)
                'N' -> out.le(Math.round((v as Number).toDouble() * Math.pow(10.0, f.digits.toDouble())), 4)
                'c' -> {
                    val b = (v as String).toByteArray(cs)
                    require(b.size <= f.digits) { "$v does not fit c${f.digits}" }
                    out.raw(b); repeat(f.digits - b.size) { out.write(' '.code) }
                }
                else -> { out.raw((v as String).toByteArray(cs)); out.write(0) }
            }
        }
        return out.toByteArray()
    }

    /** What the reader returns for a record: every non-internal field, decoded. */
    private fun decoded(type: Int, values: Map<String, Any>): Map<String, Any> =
        fields(pack[type]).filter { it.name !in setOf("region_seek", "country_seek", "country_id") }.associate { f ->
            val v = values[f.name] ?: if (f.code == 'c' || f.code == 'b') "" else 0
            f.name to when (f.code) {
                'I' -> (v as Number).toLong()
                'f' -> (v as Number).toFloat()
                'd', 'n', 'N' -> (v as Number).toDouble()
                'c' -> (v as String).trimEnd(' ')
                'b' -> v as String
                else -> (v as Number).toInt()
            }
        }

    private fun Map<String, Any>.int(k: String) = (this[k] as? Number)?.toInt() ?: 0
    private fun Map<String, Any>.dbl(k: String) = (this[k] as? Number)?.toDouble() ?: 0.0
    private fun Map<String, Any>.str(k: String) = this[k] as? String ?: ""

    fun expectedCountry(values: Map<String, Any>): Country = decoded(0, values).let {
        Country(it.int("id"), it.str("iso"), it.str("name_ru"), it.str("name_en"), it.dbl("lat"), it.dbl("lon"), it)
    }

    private fun expectedRegion(values: Map<String, Any>) = decoded(1, values).let {
        Region(it.int("id"), it.str("iso"), it.str("name_ru"), it.str("name_en"), it)
    }

    private fun expectedCity(values: Map<String, Any>) = decoded(2, values).let {
        City(it.int("id"), it.str("name_ru"), it.str("name_en"), it.dbl("lat"), it.dbl("lon"), it)
    }

    /** Brute-force answer: (countryId, country, lookup) for [ip], from the range list rather than the indexes. */
    fun expected(ip: Long): Triple<Int?, String?, GeoInfo?> {
        val none = Triple<Int?, String?, GeoInfo?>(null, null, null)
        val first = (ip ushr 24).toInt()
        if (first == 0 || first == 10 || first == 127 || first >= bIdxLen) return none
        val r = ranges.firstOrNull { ip in it.from..it.to } ?: return none
        val t = r.target
        val iso = { id: Int -> ISO_CODES.getOrNull(id)?.takeIf { id > 0 } }
        return when (t) {
            is Target.Id -> if (t.id == 0) none else Triple(t.id, iso(t.id), GeoInfo(Country(t.id, ISO_CODES.getOrElse(t.id) { "" }), null, null))
            is Target.CountryAt -> {
                val c = expectedCountry(countries.first { (it["id"] as Number).toInt() == t.id })
                if (c.id <= 0) none else Triple(c.id, iso(c.id), GeoInfo(c, null, null))
            }
            is Target.CityAt -> {
                val c = cities.getValue(t.key)
                val region = c.region?.let { regions.getValue(it) }
                val viaRegion = region?.let { reg ->
                    val seek = reg.countrySeek ?: countrySeek[reg.countryId]
                    countries.firstOrNull { countrySeek[(it["id"] as Number).toInt()] == seek }
                }?.takeIf { (it["id"] as Number).toInt() == c.countryId }
                val record = viaRegion ?: countries.firstOrNull { (it["id"] as Number).toInt() == c.countryId }
                val country = record?.let(::expectedCountry) ?: Country(c.countryId, ISO_CODES.getOrElse(c.countryId) { "" })
                Triple(c.countryId.takeIf { it > 0 }, iso(c.countryId), GeoInfo(country, region?.let { expectedRegion(it.values) }, expectedCity(c.values)))
            }
            is Target.Raw -> error("no reference answer for raw offsets")
        }
    }

    /** Boundary IPs: start-1/start/start+1/end-1/end/end+1 of every range, every block start and m_idx entry +-1, first-byte edges. */
    fun probes(): List<Long> {
        val p = sortedSetOf<Long>()
        for (r in ranges) for (x in listOf(r.from - 1, r.from, r.from + 1, r.to - 1, r.to, r.to + 1)) p.add(x)
        for (s in starts) for (x in s - 1..s + 1) p.add(x)
        for (m in mIdx) for (x in m - 1..m + 1) p.add(x)
        for (o in 0L..256L) for (x in listOf((o shl 24) - 1, o shl 24, (o shl 24) + 1, (o shl 24) or 0x7FFFFF)) p.add(x)
        return p.filter { it in 0L..0xFFFFFFFFL }
    }

    private class Out : ByteArrayOutputStream() {
        fun raw(b: ByteArray) = write(b, 0, b.size)
        fun be(v: Long, n: Int) { for (i in n - 1 downTo 0) write((v ushr (8 * i)).toInt() and 0xFF) }
        fun le(v: Long, n: Int) { for (i in 0 until n) write((v ushr (8 * i)).toInt() and 0xFF) }
    }

    companion object {
        private val NAMES_RU = listOf("Москва", "Санкт-Петербург", "Новосибирск", "Екатеринбург", "Казань", "Самара", "Омск", "Уфа")
        private val NAMES_EN = listOf("Moscow", "Saint Petersburg", "Novosibirsk", "Yekaterinburg", "Kazan", "Samara", "Omsk", "Ufa")
        private val LATIN = listOf("Zürich", "Genève", "Málaga", "Köln", "Ålesund", "São Paulo", "Montréal", "Kraków")

        /** Random database like the harness gen-spec: several first bytes, one of them with far more than rangeLen blocks. */
        fun random(
            city: Boolean, seed: Int, ranges: Int = 300, rangeLen: Int = 16, idLen: Int = if (city) 3 else 1,
            charset: Int = if (city) 0 else 1, split: Split = if (city) Split.ALWAYS else Split.EMPTY_ONLY,
            sentinel: Boolean = city, maxPack: Boolean = false,
        ): TestDb {
            val rnd = Random(seed)
            val db = TestDb(city, rangeLen, idLen, charset, split, sentinel, if (maxPack) MAX_PACK else CITY_PACK, if (maxPack) 7 else if (city) 4 else 1)
            fun name(i: Int, ru: Boolean) = when {
                charset == 1 -> LATIN[i % LATIN.size] + " $i"
                ru -> NAMES_RU[i % NAMES_RU.size] + " $i"
                else -> NAMES_EN[i % NAMES_EN.size] + " $i"
            }
            val octets = listOf(1, 2, 3, 5, 6, 7, 9, 10, 11, 12, 13, 50, 77, 126, 127, 128, 200, 222, 223)
            val weights = mapOf(5 to 0.30, 77 to 0.12)
            val list = listOf(
                "1.0.0.0" to "1.0.0.255", // very first block
                "6.0.0.0" to "6.255.255.255", // whole /8: single-block bucket
                "11.200.0.0" to "12.0.15.255", // crosses a first-byte boundary
                "7.0.0.0" to "8.255.255.255", // two whole first bytes
                "223.255.255.0" to "223.255.255.255", // last addressable
                "224.0.0.0" to "224.0.0.255", // beyond b_idx_len: never found
                "10.1.2.0" to "10.1.2.255", // reserved
                "127.0.0.0" to "127.0.0.255", // reserved
                "9.1.0.0" to "9.1.0.0", // single IP
            ).map { longArrayOf(ip2long(it.first), ip2long(it.second)) }.toMutableList()
            val rest = ranges - list.size
            for (o in octets) {
                val share = weights[o] ?: ((1 - weights.values.sum()) / (octets.size - weights.size))
                val cnt = maxOf(2, (rest * share).toInt())
                val base = o.toLong() shl 24
                val loOff = if (o == 11 || o == 12) 0x010000L else 0L
                val pts = sortedSetOf<Long>()
                while (pts.size < 2 * cnt) pts.add(base + loOff + rnd.nextLong(0x100L, 0xFFFF00L - loOff))
                val p = pts.toList()
                for (i in 0 until p.size - 1 step 2) list.add(longArrayOf(p[i], if (rnd.nextDouble() < 0.15) p[i] else p[i + 1]))
            }
            list.sortBy { it[0] }
            val clean = mutableListOf<LongArray>()
            for (r in list) if (clean.isEmpty() || r[0] > clean.last()[1]) clean.add(r)
            for (i in 0 until clean.size - 1) { // some adjacent ranges (end + 1 == next start)
                if (rnd.nextDouble() < 0.25 && clean[i][1] + 1 < clean[i + 1][0]) clean[i][1] = clean[i + 1][0] - 1
            }
            if (!city) {
                val maxId = minOf(ISO_CODES.size + 2, (1 shl (8 * idLen)) - 1) // a few ids beyond the ISO table
                for (r in clean) db.ranges.add(Range(r[0], r[1], Target.Id(rnd.nextInt(1, maxId + 1))))
                return db
            }
            val countryIds = (1 until ISO_CODES.size).shuffled(rnd).take(13)
            for ((k, cid) in countryIds.dropLast(1).withIndex()) {
                db.countries.add(mapOf(
                    "id" to cid, "iso" to ISO_CODES[cid], "lat" to rnd.nextInt(-8900, 8900) / 100.0, "lon" to rnd.nextInt(-17900, 17900) / 100.0,
                    "name_ru" to name(k, true), "name_en" to name(k, false), "timezone" to "Eu/X$k", "population" to rnd.nextLong(0, 4_000_000_000),
                    "t8" to rnd.nextInt(-128, 128), "s16" to rnd.nextInt(-32768, 32768), "area" to rnd.nextDouble(-1e6, 1e6),
                ))
            }
            val withRecord = countryIds.dropLast(1)
            for (k in 0 until 25) {
                val c = withRecord.random(rnd)
                db.regions["r$k"] = RegionDef(mapOf(
                    "id" to rnd.nextInt(1, 1 shl 24), "iso" to "${ISO_CODES[c]}-%02d".format(k), "name_ru" to name(k + 3, true),
                    "name_en" to name(k + 3, false), "okato" to listOf("${rnd.nextInt(1_000_000_000)}", " 1 2", "AB \n", "", "x  ")[k % 5],
                    "m24" to rnd.nextInt(-(1 shl 23), 1 shl 23), "i32" to rnd.nextInt(),
                ), c)
            }
            for (k in 0 until 60) {
                val r = db.regions.entries.toList().random(rnd)
                val cid = when (k) {
                    1 -> withRecord.first { it != r.value.countryId } // region points to another country
                    2 -> countryIds.last() // no record for this country in the database
                    else -> r.value.countryId
                }
                db.cities["c$k"] = CityDef(mapOf(
                    "id" to rnd.nextInt(1, 1 shl 24), "lat" to rnd.nextInt(-8_900_000, 8_900_000) / 1e5, "lon" to rnd.nextInt(-17_900_000, 17_900_000) / 1e5,
                    "name_ru" to name(k + 7, true), "name_en" to name(k + 7, false), "f32" to rnd.nextFloat() * 2000 - 1000,
                    "alt" to rnd.nextInt(-30000, 30000) / 10.0, "u16" to rnd.nextInt(65536), "u32" to rnd.nextLong(0, 1L shl 32),
                    "temp" to rnd.nextInt(-60, 60).toDouble(),
                ), if (k == 0) null else r.key, cid) // c0: city without a region
            }
            val targets = clean.map { if (rnd.nextDouble() < 0.2) Target.CountryAt(withRecord.random(rnd)) else Target.CityAt(db.cities.keys.random(rnd)) }
                .toMutableList()
            targets[1] = Target.CityAt("c0")
            targets[2] = Target.CityAt("c1")
            targets[3] = Target.CityAt("c2")
            for ((r, t) in clean.zip(targets)) db.ranges.add(Range(r[0], r[1], t))
            return db
        }
    }
}
