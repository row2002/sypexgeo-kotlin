# sypexgeo-kotlin

[Русский](README.md) | **English**

Kotlin library for reading [Sypex Geo](https://sypexgeo.net/) 2.2 databases (`SxGeo.dat`, `SxGeoCity.dat`, `SxGeoMax.dat`): country, region and city by IPv4 address.

- No dependencies besides `kotlin-stdlib`.
- Works offline with a local database file: no network calls, no request limits.
- The database is loaded into memory once; a lookup takes a few microseconds.
- Instances are immutable and thread-safe, so create one per application.
- JVM 8+ and Kotlin 1.9+, works from Java too.
- UTF-8, CP1251 and Latin-1 database editions are supported.

This is a port of the official `SxGeo.php` 2.2.3 with a fixed search: an IP equal to the first address of a range resolves to that range (see [Differences from SxGeo.php](#differences-from-sxgeophp)). Tests check the search at every range boundary of synthetic databases and on the real databases.

## Installation

Published via [JitPack](https://jitpack.io/#row2002/sypexgeo-kotlin).

**Gradle (Kotlin DSL)**

```kotlin
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.row2002:sypexgeo-kotlin:1.1.0")
}
```

**Maven**

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.row2002</groupId>
    <artifactId>sypexgeo-kotlin</artifactId>
    <version>1.1.0</version>
</dependency>
```

## Getting a database

Download a database from [sypexgeo.net/ru/download](https://sypexgeo.net/ru/download/) and unzip it:

| File | Contents | Size |
|---|---|---|
| `SxGeo.dat` ([SxGeoCountry.zip](https://sypexgeo.net/files/SxGeoCountry.zip)) | countries only | ~0.8 MB |
| `SxGeoCity.dat` ([SxGeoCity_utf8.zip](https://sypexgeo.net/files/SxGeoCity_utf8.zip)) | countries, regions, cities | ~40 MB |
| `SxGeoMax.dat` (paid City Max) | the same, plus extra fields (time zones, etc.) | — |

The databases are updated regularly. Re-download them at least once a month.

## Usage

```kotlin
import io.github.row2002.sypexgeo.SxGeo
import java.io.File

val geo = SxGeo(File("SxGeoCity.dat")) // once, at application start

geo.country("77.88.8.8")   // "RU"
geo.countryId("77.88.8.8") // 185

val info = geo.lookup("93.73.35.74")
if (info != null) {
    info.country.iso     // "UA"
    info.country.nameEn  // "Ukraine"
    info.region?.nameEn  // "Kyiv"
    info.region?.iso     // "UA-30"
    info.city?.nameEn    // "Kyiv"
    info.city?.nameRu    // "Киев"
    info.city?.lat       // 50.45466
    info.city?.lon       // 30.5238
}
```

When an IP is missing from the database, reserved (`10.*`, `127.*`, `0.*`), malformed or `null`, the methods return `null` instead of throwing.

Only dotted-decimal IPv4 is accepted: exactly four octets 0–255 of 1–3 ASCII digits without leading zeros. Spaces, `\t`, `\r` and `\n` around the address are ignored. Like PHP `ip2long`, the library rejects `"08.8.8.8"` and `"010.0.0.1"`, as well as IPv6, host names and other whitespace. If the IP comes from a header such as `X-Forwarded-For`, extract a single address first (the first or the last trusted hop): a value like `"77.88.8.8, 10.0.0.1"` as a whole returns nothing.

### From Java

```java
import io.github.row2002.sypexgeo.GeoInfo;
import io.github.row2002.sypexgeo.SxGeo;
import java.io.IOException;
import java.nio.file.Paths;

public class Main {
    public static void main(String[] args) throws IOException {
        SxGeo geo = new SxGeo(Paths.get("SxGeoCity.dat")); // IOException if the file cannot be read

        String iso = geo.country("77.88.8.8"); // "RU" or null
        GeoInfo info = geo.lookup("77.88.8.8");
        if (info != null && info.getCity() != null) {
            System.out.println(info.getCity().getNameEn()); // "Moscow"
        }
    }
}
```

For pure Java projects there is a separate library, [sypexgeo-java](https://github.com/row2002/sypexgeo-java), with a similar Java 17 style API (`Optional`, records).

### Other ways to open a database

```kotlin
SxGeo(Paths.get("/var/lib/geo/SxGeoCity.dat"))   // java.nio.file.Path
SxGeo(File("SxGeoCity.dat"))                     // java.io.File
SxGeo(javaClass.getResourceAsStream("/SxGeo.dat")!!.readBytes()) // classpath resource or any InputStream
```

The constructors throw `IllegalArgumentException` if the data is not a Sypex Geo 2.2 database, is corrupted or truncated; the `Path` and `File` constructors also throw `IOException` if the file cannot be read. The `ByteArray` constructor copies the array, so the array can be reused.

### Reloading the database without a restart

Instances are immutable, so swapping the reference is enough. If the new file is broken, the constructor throws and the old database stays in use:

```kotlin
@Volatile var geo = SxGeo(File("SxGeoCity.dat"))

fun reload() {
    geo = SxGeo(File("SxGeoCity.dat"))
}
```

## API

| Method | Returns |
|---|---|
| `SxGeo(path: Path)`, `SxGeo(file: File)`, `SxGeo(data: ByteArray)` | opens a database |
| `country(ip: String?): String?` | ISO country code (`"RU"`); works with any database |
| `countryId(ip: String?): Int?` | Sypex Geo country id |
| `lookup(ip: String?): GeoInfo?` | country, region and city |
| `country(address: Inet4Address)`, `lookup(address: Inet4Address)` | the same for `Inet4Address` |
| `info: DbInfo` | build date, edition, charset, number of IP ranges |

```kotlin
data class GeoInfo(val country: Country, val region: Region?, val city: City?)
data class Country(val id: Int, val iso: String, val nameRu: String = "", val nameEn: String = "", val lat: Double = 0.0, val lon: Double = 0.0, val fields: Map<String, Any> = emptyMap())
data class Region(val id: Int, val iso: String, val nameRu: String, val nameEn: String, val fields: Map<String, Any>)
data class City(val id: Int, val nameRu: String, val nameEn: String, val lat: Double, val lon: Double, val fields: Map<String, Any>)
```

Notes:

- With the country database (`SxGeo.dat`), `lookup` fills only `country.id` and `country.iso`; `region` and `city` are `null`.
- When only the country is known for an IP range, `city` and `region` are `null`, and `country` has its name and coordinates.
- The country always comes from the city record (`country_id`), so `lookup` and `country` never disagree. When a city has no region, or the database wrongly links the region to another country, `country` is the full record of that country from the database (with names and coordinates). Only if the database has no such record does `country` have just `id` and `iso`.
- `fields` holds all fields of the record except internal links (`region_seek`, `country_seek`, `country_id`). For City Max that includes extra fields such as `timezone`:

```kotlin
val tz = info.region?.fields?.get("timezone") as String? // City Max only
```

### Database info

```kotlin
geo.info.created   // 2026-08-12T…Z
geo.info.type      // "SxGeo City EN"
geo.info.hasCities // true
```

### Exceptions

- `IllegalArgumentException` from the constructors if the data is not a Sypex Geo 2.2 database of the supported version, is corrupted (header, indexes, record formats) or truncated.
- `IOException` from the `Path` and `File` constructors if the file cannot be read.
- `IllegalStateException` (`"Corrupted Sypex Geo database: ..."`) from `country`, `countryId` and `lookup` if a database record they need is corrupted: it points outside its section or runs past its end. Official databases never do this.

A malformed IP never causes an exception; the result is simply `null`.

## Differences from SxGeo.php

- An IP that is exactly the first address of a range resolves to that range. `SxGeo.php` 2.2.3 sometimes answers with the neighbouring range (for example, `1.4.0.0` resolves to China instead of Australia). Version 1.0.0 of this library still had the `SxGeo.php` behaviour; fixed in 1.1.0.
- The country in `lookup` comes from the city record (see [API](#api)), not from the region as in `getCityFull()`.
- A corrupted database causes an exception (see [Exceptions](#exceptions)) instead of PHP warnings and garbage results.

## Limitations

- IPv4 only, because the Sypex Geo format has no IPv6 data. IPv6 input returns `null`.
- Only format 2.2 is supported, which covers all current databases.
- Memory use equals the file size (~40 MB for City).

## Building and testing

```bash
./gradlew test
```

The main tests build synthetic databases themselves and check the answers at every range boundary against a brute-force search, so they need no downloads. The integration tests use the real databases: put `SxGeo.dat` and `SxGeoCity.dat` into `testdata/`, otherwise those tests are skipped. CI downloads the databases automatically.

## License

[MIT](LICENSE). The algorithm is based on the official `SxGeo.php` (© zapimir, BINOVATOR, BSD license). Sypex Geo databases are distributed under the terms published at [sypexgeo.net](https://sypexgeo.net/).
