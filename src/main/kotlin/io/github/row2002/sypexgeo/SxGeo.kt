package io.github.row2002.sypexgeo

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Collections
import java.util.Locale

/**
 * Reader for Sypex Geo 2.2 databases (`SxGeo.dat`, `SxGeoCity.dat`, `SxGeoMax.dat`).
 *
 * The whole file is kept in memory; instances are immutable and thread-safe.
 * Port of the official `SxGeo.php` 2.2.3, except that an IP equal to the first address of a range
 * is resolved to that range (`SxGeo.php` sometimes answers with the neighbouring range).
 *
 * IP strings are dotted IPv4: ASCII spaces, tabs, `\r` and `\n` around the address are ignored;
 * exactly four decimal octets 0..255 of one to three ASCII digits without leading zeros
 * (`08.8.8.8` and `010.0.0.1` are rejected, like PHP `ip2long`). The IPv4-mapped IPv6 form `::ffff:1.2.3.4`
 * (prefix in any case) is accepted too. Anything else, including `host:port`, gives `null`.
 *
 * The constructors check the header, the indexes and the record formats and throw [IllegalArgumentException]
 * for data that is not a valid, complete Sypex Geo 2.2 database; the message says what the data looks like
 * instead (a ZIP archive, an HTML page, a MaxMind database, an empty file), and the [Path] and [File]
 * constructors start it with the file name. Lookups never throw for a valid database;
 * if a record they need turns out to be corrupted (it points outside its section or runs past its end),
 * they throw [IllegalStateException] ("Corrupted Sypex Geo database: ..."). [country] and [countryId] decode
 * only the country id of a record, so a corrupted record is reported by [lookup] but may not be by them.
 */
class SxGeo private constructor(bytes: ByteArray, copy: Boolean, source: Any?) {

    /** Reads a database from memory; [data] is copied, later changes to the array do not affect this instance. */
    constructor(data: ByteArray) : this(data, true, null)

    /** Opens a database file; it is read into memory once. Throws [IOException] if the file cannot be read. */
    @Throws(IOException::class)
    constructor(path: Path) : this(Files.readAllBytes(path), false, path)

    /** Opens a database file; it is read into memory once. Throws [IOException] if the file cannot be read. */
    @Throws(IOException::class)
    constructor(file: File) : this(file.readBytes(), false, file)

    /**
     * Reads a database from [input] (a classpath resource, an Android asset, ...) to its end.
     * The stream is not closed. Throws [IOException] if it cannot be read.
     */
    @Throws(IOException::class)
    constructor(input: InputStream) : this(input.readBytes(), false, null)

    private val data = if (copy) bytes.copyOf() else bytes

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
    private val regionSize: Int
    private val citySize: Int
    private val countrySize: Int
    private val charset: Charset
    private val packs: List<List<Field>>
    /** The country id field of country (0) and city (2) records when it can be read directly (fixed offset), else null. */
    private val idFields: List<Field?>
    private val bIdxStart: Int
    private val mIdxStart: Int
    private val dbStart: Int
    private val regionsStart: Int
    private val citiesStart: Int
    /** Country id -> seek of its record, for cities whose region does not lead to their country. */
    private val countrySeeks: Map<Int, Int>

    init {
        try {
            require(data.size >= HEADER_SIZE && String(data, 0, 3, Charsets.ISO_8859_1) == "SxG") { notSxGeo(data) }
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
            regionSize = u32(24).toInt()
            citySize = u32(28).toInt()
            maxCountry = u16(32)
            countrySize = u32(34).toInt()
            val packSize = u16(38)
            require(bIdxLen > 0 && mIdxLen > 0 && rangeLen > 0 && dbItems > 0 && time > 0 && idLen in 1..4 &&
                regionSize >= 0 && citySize >= 0 && countrySize >= 0) {
                "Corrupted Sypex Geo database header"
            }
            require(data.size.toLong() >= HEADER_SIZE.toLong() + packSize + (bIdxLen + mIdxLen) * 4L +
                dbItems.toLong() * (3 + idLen) + regionSize + countrySize.toLong() + citySize) { "Truncated Sypex Geo database" }

            charset = when (u8(9)) {
                0 -> Charsets.UTF_8
                1 -> Charsets.ISO_8859_1
                2 -> Charset.forName("windows-1251")
                else -> throw IllegalArgumentException("Unknown database charset ${u8(9)}")
            }
            bIdxStart = HEADER_SIZE + packSize
            mIdxStart = bIdxStart + bIdxLen * 4
            dbStart = mIdxStart + mIdxLen * 4
            blockLen = 3 + idLen
            regionsStart = dbStart + dbItems * blockLen
            citiesStart = regionsStart + regionSize
            for (i in 0 until bIdxLen) {
                require(u32(bIdxStart + i * 4) <= dbItems && (i == 0 || u32(bIdxStart + i * 4) >= u32(bIdxStart + i * 4 - 4))) {
                    "Corrupted Sypex Geo database index"
                }
            }

            if (maxCity > 0) {
                val formats = String(data, HEADER_SIZE, packSize, Charsets.ISO_8859_1).split('\u0000')
                require(packSize > 0 && formats.size >= 3) { "City database without pack formats" }
                packs = formats.subList(0, 3).map(::parsePack)
                idFields = listOf(fixedField(packs[0], "id"), null, fixedField(packs[2], "country_id"))
            } else {
                packs = emptyList()
                idFields = emptyList()
            }

            val seeks = HashMap<Int, Int>()
            if (maxCity > 0 && maxCountry > 0) {
                // country records are stored back to back at the start of the cities section, after a pad byte
                var seek = 1
                try {
                    while (seek < countrySize) {
                        val record = LinkedHashMap<String, Any>()
                        val next = read(0, seek, record)
                        record.int("id").takeIf { it > 0 }?.let { seeks.putIfAbsent(it, seek) }
                        if (next <= seek) break
                        seek = next
                    }
                } catch (e: IllegalStateException) {
                    throw IllegalArgumentException("Corrupted Sypex Geo database: invalid country records", e)
                }
            }
            countrySeeks = seeks

            info = DbInfo(
                version = "2.2",
                created = Instant.ofEpochSecond(time),
                type = TYPES.getOrElse(type) { "unknown ($type)" },
                charset = charset,
                hasCities = maxCity > 0,
                ipBlocks = dbItems,
            )
        } catch (e: IllegalArgumentException) {
            throw if (source == null) e else IllegalArgumentException("$source: ${e.message}", e)
        }
    }

    /**
     * ISO 3166-1 alpha-2 country code for [ip] (dotted IPv4), or `null` if unknown. Works with any database.
     *
     * Decodes only the country id of the record (the whole record only if a variable-size field comes before the id),
     * so a corrupted record is reported by [lookup] but may not be by this method.
     * @throws IllegalStateException if the database is corrupted where the country id is read
     */
    fun country(ip: String?): String? = isoOf(idOf(parseIpv4(ip)))

    /**
     * Sypex Geo country id for [ip] (dotted IPv4), or `null` if unknown. Works with any database.
     *
     * Decodes only the country id of the record (the whole record only if a variable-size field comes before the id),
     * so a corrupted record is reported by [lookup] but may not be by this method.
     * @throws IllegalStateException if the database is corrupted where the country id is read
     */
    fun countryId(ip: String?): Int? = idOf(parseIpv4(ip)).takeIf { it > 0 }

    /**
     * Full information for [ip] (dotted IPv4): country, region and city with names and coordinates.
     * Returns `null` if the IP is unknown, reserved or malformed.
     * With the country-only database (`SxGeo.dat`) only [Country.id] and [Country.iso] are filled.
     *
     * The country always matches [countryId]: with a city database it is the city's own country, read by id
     * when the city has no region or its region belongs to another country (database errors).
     * @throws IllegalStateException if the database is corrupted
     */
    fun lookup(ip: String?): GeoInfo? = find(parseIpv4(ip))

    /**
     * Same as [lookup] for an [InetAddress], without converting it to a string.
     * An [Inet6Address] gives `null` (the JDK already returns `::ffff:1.2.3.4` as an [Inet4Address]).
     */
    fun lookup(address: InetAddress): GeoInfo? = find(toIpv4(address))

    /** Same as [country] for an [InetAddress]; an [Inet6Address] gives `null`. */
    fun country(address: InetAddress): String? = isoOf(idOf(toIpv4(address)))

    /** Same as [countryId] for an [InetAddress]; an [Inet6Address] gives `null`. */
    fun countryId(address: InetAddress): Int? = idOf(toIpv4(address)).takeIf { it > 0 }

    /** Same as [lookup] for an [Inet4Address] (kept for binary compatibility, the [InetAddress] overload does the same). */
    fun lookup(address: Inet4Address): GeoInfo? = find(toIpv4(address))

    /** Same as [country] for an [Inet4Address] (kept for binary compatibility, the [InetAddress] overload does the same). */
    fun country(address: Inet4Address): String? = isoOf(idOf(toIpv4(address)))

    private fun find(ipn: Long): GeoInfo? {
        if (ipn < 0) return null
        val num = locate(ipn)
        if (num == 0) return null
        if (maxCity == 0) return if (num > 0) GeoInfo(Country(num, ISO.getOrElse(num) { "" }), null, null) else null

        if (num < countrySize) {
            val country = toCountry(unpack(0, num, maxCountry) ?: return null)
            return if (country.id > 0) GeoInfo(country, null, null) else null
        }
        val city = unpack(2, num, maxCity) ?: return null
        val region = unpack(1, city.int("region_seek"), maxRegion)
        val countryId = city.int("country_id")
        // the city's own country_id wins: a few regions point to another country (database errors)
        val country = region?.let { unpack(0, it.int("country_seek"), maxCountry) }?.takeIf { it.int("id") == countryId }
            ?: countrySeeks[countryId]?.let { unpack(0, it, maxCountry) }
        return GeoInfo(country?.let(::toCountry) ?: Country(countryId, ISO.getOrElse(countryId) { "" }),
            region?.let(::toRegion), toCity(city))
    }

    /** Country id for the IP number [ipn] (-1: invalid), 0 or negative if not found; no boxing. */
    private fun idOf(ipn: Long): Int {
        if (ipn < 0) return 0
        val num = locate(ipn)
        if (num == 0) return 0
        // a negative offset in a city database (4-byte id with the high bit set) is corrupted: read() throws
        return if (maxCity == 0) num else countryIdAt(num)
    }

    /** Country id of the country (seek < countrySize) or city record at [seek]. */
    private fun countryIdAt(seek: Int): Int {
        val type = if (seek < countrySize) 0 else 2
        val maxLen = if (type == 0) maxCountry else maxCity
        val field = idFields[type]
        // fast path: read only the id field; it and every field before it are inside the section, so the full
        // decoding would not fail before reaching it either
        if (field != null && seek > 0 && maxLen > 0 &&
            seek.toLong() + field.offset + field.size <= (if (type == 0) countrySize else countrySize + citySize)) {
            return intValue(field, citiesStart + seek + field.offset)
        }
        return unpack(type, seek, maxLen)?.int(if (type == 0) "id" else "country_id") ?: 0
    }

    /** Returns the country id (country database) or the record offset (city database); 0 if not found. */
    private fun locate(ip: Long): Int {
        val first = (ip ushr 24).toInt()
        if (first == 0 || first == 10 || first == 127 || first >= bIdxLen) return 0

        // b_idx is validated: bMin <= bMax <= dbItems, so min..max below always stays inside the block table
        val bMin = u32(bIdxStart + (first - 1) * 4).toInt()
        val bMax = u32(bIdxStart + first * 4).toInt()
        var min = bMin
        var max = bMax
        if (bMax - bMin > rangeLen) {
            // m_idx[i] is the first IP of block (i + 1) * rangeLen; part = number of those entries <= ip
            val last = minOf(bMax / rangeLen, mIdxLen) - 1
            val part = searchIdx(ip, bMin / rangeLen, last)
            min = maxOf(part * rangeLen, bMin)
            max = if (part > last) bMax else minOf((part + 1) * rangeLen, bMax)
        }
        return searchDb(ip, min, max)
    }

    /** [from] plus the number of main index entries `<= ip` in `from..to`: the index chunk that holds [ip]. */
    private fun searchIdx(ip: Long, from: Int, to: Int): Int {
        var min = from
        var max = to
        while (max - min > 8) {
            val offset = (min + max) ushr 1
            if (ip >= u32(mIdxStart + offset * 4)) min = offset else max = offset
        }
        while (min <= max && ip >= u32(mIdxStart + min * 4)) min++
        return min
    }

    /** Id of the last block starting at or before [ip], searching blocks `from until to`; the block before [from] if none. */
    private fun searchDb(ip: Long, from: Int, to: Int): Int {
        var min = from
        var max = to
        val low = ip and 0xFFFFFF // blocks inside a first-byte range store only the 3 low bytes
        while (max - min > 8) {
            val offset = (min + max) ushr 1
            if (low >= u24(dbStart + offset * blockLen)) min = offset else max = offset
        }
        while (min < max && low >= u24(dbStart + min * blockLen)) min++
        if (min == 0) return 0
        var id = 0
        val pos = dbStart + min * blockLen - idLen
        for (i in 0 until idLen) id = (id shl 8) or u8(pos + i)
        return id
    }

    /** Decodes a record: 0 country, 1 region, 2 city. Offset 0 means "no record". */
    private fun unpack(type: Int, seek: Int, maxLen: Int): Map<String, Any>? {
        if (seek == 0 || maxLen == 0) return null
        val result = LinkedHashMap<String, Any>()
        read(type, seek, result)
        return result
    }

    /** Decodes the record at [seek] of the given type into [result]; returns the seek right after it. */
    private fun read(type: Int, seek: Int, result: MutableMap<String, Any>): Int {
        val start = if (type == 1) regionsStart else citiesStart
        val end = start + when (type) { 0 -> countrySize; 1 -> regionSize; else -> countrySize + citySize }
        if (seek < 0 || seek >= end - start) {
            throw corrupted(type, seek)
        }
        var pos = start + seek
        for (field in packs[type]) {
            val len = if (field.code == 'b') {
                var nul = pos
                while (nul < end && data[nul] != 0.toByte()) nul++
                nul - pos
            } else {
                field.size
            }
            // a 'b' string needs its NUL too
            if (len + (if (field.code == 'b') 1 else 0) > end - pos) throw corrupted(type, pos - start)
            result[field.name] = value(field, pos, len)
            pos += if (field.code == 'b') len + 1 else len
        }
        return pos - start
    }

    /** Decodes one field value of [len] bytes at [pos]. */
    private fun value(field: Field, pos: Int, len: Int): Any = when (field.code) {
        't' -> data[pos].toInt()
        'T' -> u8(pos)
        's' -> le(pos, 2).toShort().toInt()
        'S' -> le(pos, 2).toInt()
        'm' -> le(pos, 3).toInt() shl 8 shr 8
        'M' -> le(pos, 3).toInt()
        'i' -> le(pos, 4).toInt()
        'I' -> le(pos, 4)
        'f' -> Float.fromBits(le(pos, 4).toInt())
        'd' -> Double.fromBits(le(pos, 8))
        'n' -> le(pos, 2).toShort() / field.scale
        'N' -> le(pos, 4).toInt() / field.scale
        'c' -> String(data, pos, len, charset).trimEnd(' ')
        else -> String(data, pos, len, charset) // 'b'
    }

    /** The numeric field at [pos] as an Int, the same as decoding it and calling `toInt()`; integers without boxing. */
    private fun intValue(field: Field, pos: Int): Int = when (field.code) {
        't' -> data[pos].toInt()
        'T' -> u8(pos)
        's' -> le(pos, 2).toShort().toInt()
        'm' -> le(pos, 3).toInt() shl 8 shr 8
        'S', 'M', 'i', 'I' -> le(pos, field.size).toInt()
        else -> (value(field, pos, field.size) as Number).toInt()
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

    /**
     * One field of a pack format: [size] bytes ('b': NUL-terminated), 'n'/'N' values are divided by [scale];
     * [offset] from the record start, -1 if a variable-size field comes before it.
     */
    private class Field(val code: Char, val name: String, val size: Int, val scale: Double, val offset: Int)

    private companion object {
        const val HEADER_SIZE = 40
        val TYPE_NAMES = listOf("country", "region", "city")

        /** Byte size of the value of each pack code; 'c' takes it from the format, 'b' is NUL-terminated. */
        val SIZES = mapOf('t' to 1, 'T' to 1, 's' to 2, 'S' to 2, 'm' to 3, 'M' to 3, 'i' to 4, 'I' to 4,
            'f' to 4, 'd' to 8, 'n' to 2, 'N' to 4, 'c' to 0, 'b' to 0)

        fun corrupted(type: Int, seek: Int) =
            IllegalStateException("Corrupted Sypex Geo database: ${TYPE_NAMES[type]} record at offset $seek is out of bounds")

        /** Parses a pack format like `T:id/c2:iso/n2:lat/b:name_en`; empty tokens are ignored. */
        fun parsePack(format: String): List<Field> {
            var offset = 0L
            return format.split('/').filter { it.isNotEmpty() }.map { token -> parseField(token, offset).also {
                offset = if (offset < 0 || it.code == 'b') -1 else offset + it.size
            } }
        }

        fun parseField(token: String, offset: Long): Field {
            val colon = token.indexOf(':')
            val code = token[0]
            val digits = if (colon > 0) token.substring(1, colon) else ""
            val number = if (digits.isEmpty()) 0 else digits.takeIf { d -> d.all { it in '0'..'9' } }?.toIntOrNull()
            require(colon > 0 && code in SIZES && number != null &&
                if (code == 'c') digits.isNotEmpty() else digits.isEmpty() || code == 'n' || code == 'N') {
                "Invalid pack format '$token' in Sypex Geo database"
            }
            return Field(code, token.substring(colon + 1), if (code == 'c') number else SIZES.getValue(code),
                Math.pow(10.0, number.toDouble()), if (offset in 0..Int.MAX_VALUE) offset.toInt() else -1)
        }

        /** The last field called [name] (the one a decoded record keeps) if it is numeric and at a fixed offset. */
        fun fixedField(pack: List<Field>, name: String): Field? =
            pack.lastOrNull { it.name == name }?.takeIf { it.offset >= 0 && it.code != 'b' && it.code != 'c' }

        /** Explains why [data] is not a Sypex Geo database: what it looks like instead, if recognisable. */
        fun notSxGeo(data: ByteArray): String {
            var start = if (data.size >= 3 && data[0] == 0xEF.toByte() && data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte()) 3 else 0
            while (start < data.size && isSpace(data[start].toInt().toChar())) start++
            val hint = when {
                data.size >= 4 && data[0] == 'P'.code.toByte() && data[1] == 'K'.code.toByte() && data[2] == 3.toByte() && data[3] == 4.toByte() ->
                    "this is a ZIP archive - unzip it and pass the .dat file"
                start < data.size && data[start] == '<'.code.toByte() -> "looks like an HTML page - the download probably failed"
                indexOf(data, MAXMIND, maxOf(0, data.size - 128 * 1024)) >= 0 -> "this is a MaxMind .mmdb file"
                data.size < HEADER_SIZE -> "file is empty or too short (${data.size} bytes)"
                else -> return "Not a Sypex Geo database"
            }
            return "Not a Sypex Geo database: $hint"
        }

        /** MaxMind DB metadata marker. */
        val MAXMIND = byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte()) + "MaxMind.com".toByteArray(Charsets.ISO_8859_1)

        fun indexOf(data: ByteArray, what: ByteArray, from: Int): Int {
            outer@ for (i in from..data.size - what.size) {
                for (k in what.indices) if (data[i + k] != what[k]) continue@outer
                return i
            }
            return -1
        }

        /** The address as an unsigned 32-bit number, or -1 for IPv6. */
        fun toIpv4(address: InetAddress): Long {
            val b = address.address
            if (b.size != 4) return -1
            return ((b[0].toLong() and 0xFF) shl 24) or ((b[1].toLong() and 0xFF) shl 16) or ((b[2].toLong() and 0xFF) shl 8) or (b[3].toLong() and 0xFF)
        }

        fun isoOf(id: Int): String? = if (id > 0) ISO.getOrNull(id) else null

        /** Dotted IPv4 (optionally `::ffff:`-prefixed) as an unsigned 32-bit number, or -1 if invalid; no allocations or boxing. */
        fun parseIpv4(ip: String?): Long {
            if (ip == null) return -1
            var pos = 0
            var end = ip.length
            while (pos < end && isSpace(ip[pos])) pos++
            while (end > pos && isSpace(ip[end - 1])) end--
            if (end - pos > 7 && ip.regionMatches(pos, "::ffff:", 0, 7, ignoreCase = true)) pos += 7 // IPv4-mapped IPv6
            var result = 0L
            for (octet in 0 until 4) {
                if (octet > 0 && (pos >= end || ip[pos++] != '.')) return -1
                val start = pos
                var value = 0
                while (pos < end && pos - start < 4 && ip[pos] in '0'..'9') value = value * 10 + (ip[pos++] - '0')
                val len = pos - start
                if (len == 0 || len > 3 || value > 255 || len > 1 && ip[start] == '0') return -1
                result = (result shl 8) or value.toLong()
            }
            return if (pos == end) result else -1
        }

        fun isSpace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r'

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

/**
 * Lookup result. [region] and [city] are `null` when the database (or the IP range) has no such data.
 * [country] is the city's own country, even when the region record points to another one.
 */
data class GeoInfo(val country: Country, val region: Region?, val city: City?)

/**
 * Country record. [fields] holds all fields of the record except internal links (`region_seek`, `country_seek`,
 * `country_id`), including extra City Max fields (`timezone`, `capital_id`, `population`, ...).
 * Only [id] and [iso] are filled (and [fields] is empty) with the country-only database,
 * or when a city database has no record for the city's country.
 */
data class Country(
    val id: Int,
    val iso: String,
    val nameRu: String = "",
    val nameEn: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val fields: Map<String, Any> = emptyMap(),
) {
    /** [nameRu] for a Russian [locale] (language `ru`) if it is not empty, otherwise [nameEn], or [nameRu] if [nameEn] is empty. */
    fun name(locale: Locale): String = if (locale.language == "ru" && nameRu.isNotEmpty() || nameEn.isEmpty()) nameRu else nameEn
}

/**
 * Region record; [iso] is ISO 3166-2, e.g. `RU-MOW`.
 * [fields] holds all fields of the record except internal links (`region_seek`, `country_seek`, `country_id`).
 */
data class Region(
    val id: Int,
    val iso: String,
    val nameRu: String,
    val nameEn: String,
    val fields: Map<String, Any>,
) {
    /** [nameRu] for a Russian [locale] (language `ru`) if it is not empty, otherwise [nameEn], or [nameRu] if [nameEn] is empty. */
    fun name(locale: Locale): String = if (locale.language == "ru" && nameRu.isNotEmpty() || nameEn.isEmpty()) nameRu else nameEn
}

/** City record. [fields] holds all fields of the record except internal links (`region_seek`, `country_seek`, `country_id`). */
data class City(
    val id: Int,
    val nameRu: String,
    val nameEn: String,
    val lat: Double,
    val lon: Double,
    val fields: Map<String, Any>,
) {
    /** [nameRu] for a Russian [locale] (language `ru`) if it is not empty, otherwise [nameEn], or [nameRu] if [nameEn] is empty. */
    fun name(locale: Locale): String = if (locale.language == "ru" && nameRu.isNotEmpty() || nameEn.isEmpty()) nameRu else nameEn
}

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
