# sypexgeo-kotlin

[Русский](README.md) | **English**

Kotlin library for reading [Sypex Geo](https://sypexgeo.net/) 2.2 databases (`SxGeo.dat`, `SxGeoCity.dat`, `SxGeoMax.dat`): country, region and city by IPv4 address.

- No dependencies besides `kotlin-stdlib`.
- Works offline with a local database file: no network calls, no request limits.
- The database is loaded into memory once; a lookup takes a few microseconds.
- Instances are immutable and thread-safe, so create one per application.
- JVM 8+ and Kotlin 1.9+, works from Java too.
- UTF-8, CP1251 and Latin-1 database editions are supported.

This is a port of the official `SxGeo.php` 2.2.3. Results were cross-checked on 30,000 random IPs against [pysyge](https://github.com/idlesign/pysyge), an independent port of `SxGeo.php`.

## Installation

Published via [JitPack](https://jitpack.io/#row2002/sypexgeo-kotlin).

**Gradle (Kotlin DSL)**

```kotlin
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.row2002:sypexgeo-kotlin:1.0.0")
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
    <version>1.0.0</version>
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

When an IP is missing from the database, reserved (`10.*`, `127.*`, `0.*`), malformed or `null`, the methods return `null` instead of throwing. If the IP comes from a header such as `X-Forwarded-For`, extract a single address first (the first or the last trusted hop): a value like `"77.88.8.8, 10.0.0.1"` as a whole returns nothing.

### From Java

```java
SxGeo geo = new SxGeo(Paths.get("SxGeoCity.dat"));

String iso = geo.country("77.88.8.8"); // "RU" or null
GeoInfo info = geo.lookup("77.88.8.8");
if (info != null && info.getCity() != null) {
    System.out.println(info.getCity().getNameEn()); // "Moscow"
}
```

For pure Java projects there is a separate library, [sypexgeo-java](https://github.com/row2002/sypexgeo-java), with a similar Java 17 style API (`Optional`, records).

### Other ways to open a database

```kotlin
SxGeo(Paths.get("/var/lib/geo/SxGeoCity.dat"))   // java.nio.file.Path
SxGeo(File("SxGeoCity.dat"))                     // java.io.File
SxGeo(javaClass.getResourceAsStream("/SxGeo.dat")!!.readBytes()) // classpath resource or any InputStream
```

The constructor throws `IllegalArgumentException` if the data is not a Sypex Geo 2.2 database or is truncated.

### Reloading the database without a restart

Instances are immutable, so swapping the reference is enough:

```kotlin
@Volatile var geo = SxGeo(File("SxGeoCity.dat"))

fun reload() {
    geo = SxGeo(File("SxGeoCity.dat"))
}
```

## API

| Method | Returns |
|---|---|
| `country(ip: String): String?` | ISO country code (`"RU"`); works with any database |
| `countryId(ip: String): Int?` | Sypex Geo country id |
| `lookup(ip: String): GeoInfo?` | country, region and city |
| `country(address: Inet4Address)`, `lookup(address: Inet4Address)` | the same for `Inet4Address` |
| `info: DbInfo` | build date, edition, charset, number of IP ranges |

```kotlin
data class GeoInfo(val country: Country, val region: Region?, val city: City?)
data class Country(val id: Int, val iso: String, val nameRu: String, val nameEn: String, val lat: Double, val lon: Double, val fields: Map<String, Any>)
data class Region(val id: Int, val iso: String, val nameRu: String, val nameEn: String, val fields: Map<String, Any>)
data class City(val id: Int, val nameRu: String, val nameEn: String, val lat: Double, val lon: Double, val fields: Map<String, Any>)
```

Notes:

- With the country database (`SxGeo.dat`), `lookup` fills only `country.id` and `country.iso`; `region` and `city` are `null`.
- When only the country is known for an IP range, `city` and `region` are `null`, and `country` has its name and coordinates.
- When a city has no region, `region` is `null` and `country` has only `id` and `iso`. The same `country` is returned when the database wrongly links the region to another country: the country always comes from the city record, so `lookup` and `country` never disagree.
- `fields` holds every field of the record as stored in the database. For City Max that includes extra fields such as `timezone`:

```kotlin
val tz = info.region?.fields?.get("timezone") as String? // City Max only
```

### Database info

```kotlin
geo.info.created   // 2026-08-12T…Z
geo.info.type      // "SxGeo City EN"
geo.info.hasCities // true
```

## Limitations

- IPv4 only, because the Sypex Geo format has no IPv6 data. IPv6 input returns `null`.
- Only format 2.2 is supported, which covers all current databases.
- Like `SxGeo.php`, an IP that is exactly the first address of a range sometimes resolves to the neighbouring range. This quirk of the reference algorithm is kept for compatibility.
- Memory use equals the file size (~40 MB for City).

## Building and testing

```bash
./gradlew test
```

The integration tests use the real databases. Put `SxGeo.dat` and `SxGeoCity.dat` into `testdata/`, otherwise those tests are skipped. CI downloads the databases automatically.

## License

[MIT](LICENSE). The algorithm is based on the official `SxGeo.php` (© zapimir, BINOVATOR, BSD license). Sypex Geo databases are distributed under the terms published at [sypexgeo.net](https://sypexgeo.net/).
