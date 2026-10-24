package io.github.row2002.sypexgeo

import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Collections

/**
 * Reader for Sypex Geo 2.2 databases (`SxGeo.dat`, `SxGeoCity.dat`, `SxGeoMax.dat`).
 *
 * The whole file is kept in memory; instances are immutable and thread-safe.
 * Port of the official `SxGeo.php` 2.2.3.
 */
class SxGeo(private val data: ByteArray) {

    /** Opens a database file; it is read into memory once. */
    @Throws(IOException::class)
    constructor(path: Path) : this(Files.readAllBytes(path))

    /** Opens a database file; it is read into memory once. */
    @Throws(IOException::class)
    constructor(file: File) : this(file.readBytes())

    val info: DbInfo

    private val rangeLen: Int
    private val bIdxLen: Int
    private val mIdxLen: Int
    private val dbItems: Int
    private val idLen: Int
    private val blockLen: Int
    private val maxRegion: Int
    private val maxCity: Int
    private val maxCountry: Int
    private val countrySize: Int
    private val charset: Charset
    private val pack: List<String>
    private val bIdxStart: Int
    private val mIdxStart: Int
    private val dbStart: Int
    private val regionsStart: Int
    private val citiesStart: Int

    init {
        require(data.size >= HEADER_SIZE && String(data, 0, 3, Charsets.ISO_8859_1) == "SxG") {
            "Not a Sypex Geo database"
        }
        val version = u8(3)
        require(version == 22) { "Unsupported Sypex Geo database version ${version / 10}.${version % 10}, need 2.2" }

        val time = u32(4)
        val type = u8(8)
        bIdxLen = u8(10)
        mIdxLen = u16(11)
        rangeLen = u16(13)
        dbItems = u32(15).toInt()
        idLen = u8(19)
        maxRegion = u16(20)
        maxCity = u16(22)
        val regionSize = u32(24).toInt()
        val citySize = u32(28).toInt()
        maxCountry = u16(32)
        countrySize = u32(34).toInt()
        val packSize = u16(38)
        require(bIdxLen > 0 && mIdxLen > 0 && rangeLen > 0 && dbItems > 0 && time > 0 && idLen > 0 &&
            regionSize >= 0 && citySize >= 0 && countrySize >= 0) {
            "Corrupted Sypex Geo database header"
        }
        require(data.size.toLong() >= HEADER_SIZE.toLong() + packSize + (bIdxLen + mIdxLen) * 4L +
            dbItems.toLong() * (3 + idLen) + regionSize + citySize) { "Truncated Sypex Geo database" }

        charset = when (u8(9)) {
            0 -> Charsets.UTF_8
            1 -> Charsets.ISO_8859_1
            2 -> Charset.forName("windows-1251")
            else -> throw IllegalArgumentException("Unknown database charset ${u8(9)}")
        }
        pack = if (packSize > 0) String(data, HEADER_SIZE, packSize, Charsets.ISO_8859_1).split('\u0000') else emptyList()
        bIdxStart = HEADER_SIZE + packSize
        mIdxStart = bIdxStart + bIdxLen * 4
        dbStart = mIdxStart + mIdxLen * 4
        blockLen = 3 + idLen
        regionsStart = dbStart + dbItems * blockLen
        citiesStart = regionsStart + regionSize
        require(maxCity == 0 || pack.size >= 3) { "City database without pack formats" }

        info = DbInfo(
            version = "2.2",
            created = Instant.ofEpochSecond(time),
            type = TYPES.getOrElse(type) { "unknown ($type)" },
            charset = charset,
            hasCities = maxCity > 0,
            ipBlocks = dbItems,
        )
    }

    /** ISO 3166-1 alpha-2 country code for [ip] (dotted IPv4), or `null` if unknown. Works with any database. */
    fun country(ip: String?): String? = countryId(ip)?.let { ISO.getOrNull(it) }

    /** Sypex Geo country id for [ip] (dotted IPv4), or `null` if unknown. Works with any database. */
    fun countryId(ip: String?): Int? {
        val num = parseIpv4(ip)?.let(::locate) ?: return null
        val id = if (maxCity == 0) num else countryIdAt(num)
        return id.takeIf { it > 0 }
    }

    /**
     * Full information for [ip] (dotted IPv4): country, region and city with names and coordinates.
     * Returns `null` if the IP is unknown, reserved or malformed.
     * With the country-only database (`SxGeo.dat`) only [Country.id] and [Country.iso] are filled.
     */
    fun lookup(ip: String?): GeoInfo? {
        val num = parseIpv4(ip)?.let(::locate) ?: return null
        if (maxCity == 0) return if (num > 0) GeoInfo(Country(num, ISO.getOrElse(num) { "" }), null, null) else null

        if (num < countrySize) {
            val country = toCountry(unpack(0, num, maxCountry) ?: return null)
            return if (country.id > 0) GeoInfo(country, null, null) else null
        }
        val city = unpack(2, num, maxCity) ?: return null
        val region = unpack(1, city.int("region_seek"), maxRegion)
        val countryId = city.int("country_id")
        // the city's own country_id wins: a few regions point to another country (database errors)
        val country = region?.let { unpack(0, it.int("country_seek"), maxCountry) }?.let(::toCountry)
            ?.takeIf { it.id == countryId }
            ?: Country(countryId, ISO.getOrElse(countryId) { "" })
        return GeoInfo(country, region?.let(::toRegion), toCity(city))
    }

    /** Same as [lookup] for an [Inet4Address]. */
    fun lookup(address: Inet4Address): GeoInfo? = lookup(address.hostAddress)

    /** Same as [country] for an [Inet4Address]. */
    fun country(address: Inet4Address): String? = country(address.hostAddress)

    private fun countryIdAt(seek: Int): Int =
        if (seek < countrySize) unpack(0, seek, maxCountry)?.int("id") ?: 0
        else unpack(2, seek, maxCity)?.int("country_id") ?: 0

    /** Returns the country id (country database) or the record offset (city database); 0 if not found. */
    private fun locate(ip: Long): Int {
        val first = (ip ushr 24).toInt()
        if (first == 0 || first == 10 || first == 127 || first >= bIdxLen) return 0

        val bMin = u32(bIdxStart + (first - 1) * 4).toInt()
        val bMax = u32(bIdxStart + first * 4).toInt()
        var min: Int
        var max: Int
        if (bMax - bMin > rangeLen) {
            val part = searchIdx(ip, bMin / rangeLen, bMax / rangeLen - 1)
            min = if (part > 0) part * rangeLen else 0
            max = if (part > mIdxLen) dbItems else (part + 1) * rangeLen
            if (min < bMin) min = bMin
            if (max > bMax) max = bMax
        } else {
            min = bMin
            max = bMax
        }
        return searchDb(ip, min, max)
    }

    private fun searchIdx(ip: Long, from: Int, to: Int): Int {
        var min = from
        var max = to
        while (max - min > 8) {
            val offset = (min + max) ushr 1
            if (ip > u32(mIdxStart + offset * 4)) min = offset else max = offset
        }
        while (ip > u32(mIdxStart + min * 4) && min++ < max) { /* linear tail */ }
        return min
    }

    private fun searchDb(ip: Long, from: Int, to: Int): Int {
        var min = from
        var max = to
        if (max - min > 1) {
            val low = ip and 0xFFFFFF // blocks inside a first-byte range store only the 3 low bytes
            while (max - min > 8) {
                val offset = (min + max) ushr 1
                if (low > u24(dbStart + offset * blockLen)) min = offset else max = offset
            }
            while (low >= u24(dbStart + min * blockLen) && ++min < max) { /* linear tail */ }
        } else {
            min++
        }
        var id = 0
        val pos = dbStart + min * blockLen - idLen
        for (i in 0 until idLen) id = (id shl 8) or u8(pos + i)
        return id
    }

    /** Decodes a record using the database pack format: 0 country, 1 region, 2 city. Offset 0 means "no record". */
    private fun unpack(type: Int, seek: Int, maxLen: Int): Map<String, Any>? {
        if (seek == 0 || maxLen == 0) return null
        var pos = (if (type == 1) regionsStart else citiesStart) + seek
        val result = LinkedHashMap<String, Any>()
        for (field in pack[type].split('/')) {
            val code = field.substringBefore(':')
            val name = field.substringAfter(':')
            val value: Any = when (code[0]) {
                't' -> data[pos].toInt().also { pos += 1 }
                'T' -> u8(pos).also { pos += 1 }
                's' -> le(pos, 2).toShort().toInt().also { pos += 2 }
                'S' -> le(pos, 2).toInt().also { pos += 2 }
                'm' -> (le(pos, 3).toInt() shl 8 shr 8).also { pos += 3 }
                'M' -> le(pos, 3).toInt().also { pos += 3 }
                'i' -> le(pos, 4).toInt().also { pos += 4 }
                'I' -> le(pos, 4).also { pos += 4 }
                'f' -> Float.fromBits(le(pos, 4).toInt()).also { pos += 4 }
                'd' -> Double.fromBits(le(pos, 8)).also { pos += 8 }
                'n' -> (le(pos, 2).toShort() / pow10(code)).also { pos += 2 }
                'N' -> (le(pos, 4).toInt() / pow10(code)).also { pos += 4 }
                'c' -> {
                    val len = code.substring(1).toInt()
                    String(data, pos, len, charset).trimEnd(' ').also { pos += len }
                }
                'b' -> {
                    var end = pos
                    while (data[end] != 0.toByte()) end++
                    String(data, pos, end - pos, charset).also { pos = end + 1 }
                }
                else -> throw IllegalStateException("Unknown pack type '$code' in database")
            }
            result[name] = value
        }
        return result
    }

    private fun u8(pos: Int): Int = data[pos].toInt() and 0xFF
    private fun u16(pos: Int): Int = (u8(pos) shl 8) or u8(pos + 1)
    private fun u24(pos: Int): Long = ((u8(pos) shl 16) or (u8(pos + 1) shl 8) or u8(pos + 2)).toLong()
    private fun u32(pos: Int): Long = (u16(pos).toLong() shl 16) or u16(pos + 2).toLong()

    /** Little-endian unsigned integer of [len] bytes. */
    private fun le(pos: Int, len: Int): Long {
        var v = 0L
        for (i in len - 1 downTo 0) v = (v shl 8) or u8(pos + i).toLong()
        return v
    }

    private companion object {
        const val HEADER_SIZE = 40

        fun pow10(code: String): Double = Math.pow(10.0, code.substring(1).toDouble())

        fun parseIpv4(ip: String?): Long? {
            val parts = ip?.trim()?.split('.') ?: return null
            if (parts.size != 4) return null
            var result = 0L
            for (part in parts) {
                if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return null
                val octet = part.toInt()
                if (octet > 255) return null
                result = (result shl 8) or octet.toLong()
            }
            return result
        }

        fun Map<String, Any>.int(key: String): Int = (this[key] as? Number)?.toInt() ?: 0
        fun Map<String, Any>.double(key: String): Double = (this[key] as? Number)?.toDouble() ?: 0.0
        fun Map<String, Any>.string(key: String): String = this[key] as? String ?: ""
        fun Map<String, Any>.withoutInternal(): Map<String, Any> = Collections.unmodifiableMap(filterKeys { it !in INTERNAL_KEYS })

        val INTERNAL_KEYS = setOf("region_seek", "country_seek", "country_id")

        fun toCountry(m: Map<String, Any>) =
            Country(m.int("id"), m.string("iso"), m.string("name_ru"), m.string("name_en"), m.double("lat"), m.double("lon"), m.withoutInternal())

        fun toRegion(m: Map<String, Any>) =
            Region(m.int("id"), m.string("iso"), m.string("name_ru"), m.string("name_en"), m.withoutInternal())

        fun toCity(m: Map<String, Any>) =
            City(m.int("id"), m.string("name_ru"), m.string("name_en"), m.double("lat"), m.double("lon"), m.withoutInternal())

        val TYPES = listOf(
            "n/a", "SxGeo Country", "SxGeo City RU", "SxGeo City EN", "SxGeo City",
            "SxGeo City Max RU", "SxGeo City Max EN", "SxGeo City Max",
        )

        /** Sypex Geo country id -> ISO code, copied from SxGeo.php. */
        val ISO = listOf(
            "", "AP", "EU", "AD", "AE", "AF", "AG", "AI", "AL", "AM", "CW", "AO", "AQ", "AR", "AS", "AT", "AU",
            "AW", "AZ", "BA", "BB", "BD", "BE", "BF", "BG", "BH", "BI", "BJ", "BM", "BN", "BO", "BR", "BS",
            "BT", "BV", "BW", "BY", "BZ", "CA", "CC", "CD", "CF", "CG", "CH", "CI", "CK", "CL", "CM", "CN",
            "CO", "CR", "CU", "CV", "CX", "CY", "CZ", "DE", "DJ", "DK", "DM", "DO", "DZ", "EC", "EE", "EG",
            "EH", "ER", "ES", "ET", "FI", "FJ", "FK", "FM", "FO", "FR", "SX", "GA", "GB", "GD", "GE", "GF",
            "GH", "GI", "GL", "GM", "GN", "GP", "GQ", "GR", "GS", "GT", "GU", "GW", "GY", "HK", "HM", "HN",
            "HR", "HT", "HU", "ID", "IE", "IL", "IN", "IO", "IQ", "IR", "IS", "IT", "JM", "JO", "JP", "KE",
            "KG", "KH", "KI", "KM", "KN", "KP", "KR", "KW", "KY", "KZ", "LA", "LB", "LC", "LI", "LK", "LR",
            "LS", "LT", "LU", "LV", "LY", "MA", "MC", "MD", "MG", "MH", "MK", "ML", "MM", "MN", "MO", "MP",
            "MQ", "MR", "MS", "MT", "MU", "MV", "MW", "MX", "MY", "MZ", "NA", "NC", "NE", "NF", "NG", "NI",
            "NL", "NO", "NP", "NR", "NU", "NZ", "OM", "PA", "PE", "PF", "PG", "PH", "PK", "PL", "PM", "PN",
            "PR", "PS", "PT", "PW", "PY", "QA", "RE", "RO", "RU", "RW", "SA", "SB", "SC", "SD", "SE", "SG",
            "SH", "SI", "SJ", "SK", "SL", "SM", "SN", "SO", "SR", "ST", "SV", "SY", "SZ", "TC", "TD", "TF",
            "TG", "TH", "TJ", "TK", "TM", "TN", "TO", "TL", "TR", "TT", "TV", "TW", "TZ", "UA", "UG", "UM",
            "US", "UY", "UZ", "VA", "VC", "VE", "VG", "VI", "VN", "VU", "WF", "WS", "YE", "YT", "RS", "ZA",
            "ZM", "ME", "ZW", "A1", "XK", "O1", "AX", "GG", "IM", "JE", "BL", "MF", "BQ", "SS",
        )
    }
}

/** Lookup result. [region] and [city] are `null` when the database (or the IP range) has no such data. */
data class GeoInfo(val country: Country, val region: Region?, val city: City?)

/**
 * Country record. [fields] holds every field stored in the database,
 * including extra City Max fields (`timezone`, `capital_id`, `population`, ...).
 */
data class Country(
    val id: Int,
    val iso: String,
    val nameRu: String = "",
    val nameEn: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val fields: Map<String, Any> = emptyMap(),
)

/** Region record; [iso] is ISO 3166-2, e.g. `RU-MOW`. [fields] holds every stored field. */
data class Region(
    val id: Int,
    val iso: String,
    val nameRu: String,
    val nameEn: String,
    val fields: Map<String, Any>,
)

/** City record. [fields] holds every stored field. */
data class City(
    val id: Int,
    val nameRu: String,
    val nameEn: String,
    val lat: Double,
    val lon: Double,
    val fields: Map<String, Any>,
)

/** Database header: build date, edition and charset. */
data class DbInfo(
    val version: String,
    val created: Instant,
    val type: String,
    val charset: Charset,
    /** `false` for the country-only database `SxGeo.dat`. */
    val hasCities: Boolean,
    val ipBlocks: Int,
)
