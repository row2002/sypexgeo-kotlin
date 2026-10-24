# sypexgeo-kotlin

[![CI](https://github.com/row2002/sypexgeo-kotlin/actions/workflows/ci.yml/badge.svg)](https://github.com/row2002/sypexgeo-kotlin/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.row2002/sypexgeo-kotlin)](https://central.sonatype.com/artifact/io.github.row2002/sypexgeo-kotlin)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

[Русский](README.md) | **English**

Kotlin library for reading [Sypex Geo](https://sypexgeo.net/) 2.2 databases (`SxGeo.dat`, `SxGeoCity.dat`, `SxGeoMax.dat`): country, region and city by IPv4 address. JVM 8+, Kotlin 1.9+, Android, works from Java too.

## Quick start

1. Add the dependency (`build.gradle.kts`):

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.row2002:sypexgeo-kotlin:1.1.0")
}
```

2. Download a database:

```bash
curl -fLO https://sypexgeo.net/files/SxGeoCity_utf8.zip && unzip SxGeoCity_utf8.zip SxGeoCity.dat
```

3. Look up an IP:

```kotlin
import io.github.row2002.sypexgeo.SxGeo
import java.io.File

val geo = SxGeo(File("SxGeoCity.dat"))           // once, at application start; thread-safe
println(geo.country("77.88.8.8"))                // RU
println(geo.lookup("77.88.8.8")?.city?.nameEn)   // Moscow
```

## Contents

- [Which library?](#which-library)
- [Features](#features)
- [Installation](#installation)
- [Getting a database](#getting-a-database)
- [Usage](#usage)
- [API](#api)
- [Recipes](#recipes): [Spring Boot](#spring-boot), [client IP behind a proxy](#client-ip-behind-a-proxy), [servlet filter](#servlet-filter), [Ktor](#ktor), [reloading without a restart](#reloading-the-database-without-a-restart), [Android](#android)
- [FAQ](#faq)
- [Sypex Geo vs MaxMind GeoLite2](#sypex-geo-vs-maxmind-geolite2)
- [Performance](#performance)
- [Differences from SxGeo.php](#differences-from-sxgeophp)
- [Building and testing](#building-and-testing)
- [License and data](#license-and-data)

## Which library?

| Your project | Library |
|---|---|
| Java 17+ | [sypexgeo-java](https://github.com/row2002/sypexgeo-java): `Optional`, records, no dependencies |
| Kotlin, Android, Java 8–16 | [sypexgeo-kotlin](https://github.com/row2002/sypexgeo-kotlin): nullable types, data classes, JVM 8+, depends only on `kotlin-stdlib` |
| Kotlin on Java 17+ | either; sypexgeo-java has JSpecify annotations, so Kotlin sees its nullability correctly |

Never add both: they have the same package and class names (`io.github.row2002.sypexgeo.SxGeo`). The behaviour is the same, including IP parsing and error messages.

## Features

- No dependencies besides `kotlin-stdlib`.
- Works offline with a local database file: no network calls, no request limits, no account.
- The database is loaded into memory once; a lookup takes about 2 µs, a country lookup well under 1 µs (see [Performance](#performance)).
- Instances are immutable and thread-safe, so create one per application.
- UTF-8, CP1251 and Latin-1 database editions are supported.
- A port of the official `SxGeo.php` 2.2.3 with a fixed search: an IP equal to the first address of a range resolves to that range (see [Differences from SxGeo.php](#differences-from-sxgeophp)). Every range boundary is checked on synthetic databases in the tests; tests on the real databases check random addresses.

## Installation

Published to [Maven Central](https://central.sonatype.com/artifact/io.github.row2002/sypexgeo-kotlin).

**Gradle (Kotlin DSL)**

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.row2002:sypexgeo-kotlin:1.1.0")
}
```

**Gradle (Groovy DSL)**

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation 'io.github.row2002:sypexgeo-kotlin:1.1.0'
}
```

**Version catalog** (`gradle/libs.versions.toml`)

```toml
[versions]
sypexgeo = "1.1.0"

[libraries]
sypexgeo = { module = "io.github.row2002:sypexgeo-kotlin", version.ref = "sypexgeo" }
```

```kotlin
dependencies {
    implementation(libs.sypexgeo)
}
```

**Maven**

```xml
<dependency>
    <groupId>io.github.row2002</groupId>
    <artifactId>sypexgeo-kotlin</artifactId>
    <version>1.1.0</version>
</dependency>
```

The older version 1.0.0 is published only on JitPack, as `com.github.row2002:sypexgeo-kotlin:1.0.0`.

## Getting a database

Download a database from [sypexgeo.net/ru/download](https://sypexgeo.net/ru/download/) and unzip it:

| File | Contents | Size |
|---|---|---|
| `SxGeo.dat` ([SxGeoCountry.zip](https://sypexgeo.net/files/SxGeoCountry.zip)) | countries only | ~1 MB |
| `SxGeoCity.dat` ([SxGeoCity_utf8.zip](https://sypexgeo.net/files/SxGeoCity_utf8.zip)) | countries, regions, cities | a few tens of MB |
| `SxGeoMax.dat` (paid City Max) | the same, plus extra fields (time zones, etc.) | — |

```bash
curl -fLO https://sypexgeo.net/files/SxGeoCountry.zip && unzip SxGeoCountry.zip SxGeo.dat
curl -fLO https://sypexgeo.net/files/SxGeoCity_utf8.zip && unzip SxGeoCity_utf8.zip SxGeoCity.dat
```

The databases are updated regularly: the download page and `info.created` show the build date, and a new database can be picked up without a restart (see [reloading without a restart](#reloading-the-database-without-a-restart)).

## Usage

```kotlin
import io.github.row2002.sypexgeo.SxGeo
import java.io.File
import java.util.Locale

val geo = SxGeo(File("SxGeoCity.dat")) // once, at application start

geo.country("77.88.8.8")   // "RU"
geo.countryId("77.88.8.8") // 185

val info = geo.lookup("93.73.35.74")
if (info != null) {
    info.country.iso                 // "UA"
    info.country.nameEn              // "Ukraine"
    info.country.name(Locale("ru"))  // "Украина"
    info.region?.nameEn              // "Kyiv"
    info.region?.iso                 // "UA-30"
    info.city?.nameEn                // "Kiev"
    info.city?.name(Locale.ENGLISH)  // "Kiev"
    info.city?.lat                   // 50.45466
    info.city?.lon                   // 30.5238
}
```

When an IP is missing from the database, reserved (`10.*`, `127.*`, `0.*`), malformed or `null`, the methods return `null` instead of throwing.

### From Java

```java
import io.github.row2002.sypexgeo.GeoInfo;
import io.github.row2002.sypexgeo.SxGeo;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Locale;

public class Main {
    public static void main(String[] args) throws IOException {
        SxGeo geo = new SxGeo(Paths.get("SxGeoCity.dat")); // IOException if the file cannot be read

        String iso = geo.country("77.88.8.8"); // "RU" or null
        GeoInfo info = geo.lookup("77.88.8.8");
        if (info != null && info.getCity() != null) {
            System.out.println(info.getCity().name(Locale.ENGLISH)); // "Moscow"
        }
        System.out.println(geo.getInfo().getCreated()); // database build date
    }
}
```

For Java 17+ projects there is [sypexgeo-java](https://github.com/row2002/sypexgeo-java) with the same API in Java style (`Optional`, records), see [Which library?](#which-library).

### Other ways to open a database

```kotlin
SxGeo(Paths.get("/var/lib/geo/SxGeoCity.dat"))           // java.nio.file.Path
SxGeo(File("SxGeoCity.dat"))                             // java.io.File
javaClass.getResourceAsStream("/SxGeo.dat")!!.use(::SxGeo) // InputStream, e.g. a classpath resource
SxGeo(File("SxGeo.dat").readBytes())                     // ByteArray
```

The `InputStream` constructor reads the stream to its end and does not close it, hence `use`. The `ByteArray` constructor copies the array, so the array can be reused.

### InetAddress

```kotlin
import java.net.InetAddress

geo.country(InetAddress.getByName("77.88.8.8"))        // "RU"
geo.lookup(InetAddress.getByName("::ffff:77.88.8.8"))  // the same as for 77.88.8.8
geo.countryId(InetAddress.getByName("2a02:6b8::feed")) // null: IPv6
```

`lookup`, `country` and `countryId` take an `InetAddress` too; the address bytes are used directly, without a string round-trip. An `Inet6Address` gives `null` (the JDK already turns `::ffff:a.b.c.d` into an `Inet4Address`). Use only addresses you already have: `InetAddress.getByName` with a host name makes a DNS query.

## API

| Method | Returns |
|---|---|
| `SxGeo(path: Path)`, `SxGeo(file: File)` | opens a database file; `IOException` if it cannot be read |
| `SxGeo(input: InputStream)` | reads the stream to its end, does not close it |
| `SxGeo(data: ByteArray)` | reads a database from memory; the array is copied |
| `country(ip: String?): String?` | ISO country code (`"RU"`); works with any database |
| `countryId(ip: String?): Int?` | Sypex Geo country id |
| `lookup(ip: String?): GeoInfo?` | country, region and city |
| `country(address: InetAddress)`, `countryId(address: InetAddress)`, `lookup(address: InetAddress)` | the same for an `InetAddress`; `null` for IPv6 |
| `info: DbInfo` | build date, edition, charset, number of IP ranges |
| `name(locale: Locale): String` on `Country`, `Region`, `City` | `nameRu` for Russian (`locale.language == "ru"`) if not empty, otherwise `nameEn`, or `nameRu` if `nameEn` is empty |

```kotlin
data class GeoInfo(val country: Country, val region: Region?, val city: City?)
data class Country(val id: Int, val iso: String, val nameRu: String = "", val nameEn: String = "", val lat: Double = 0.0, val lon: Double = 0.0, val fields: Map<String, Any> = emptyMap())
data class Region(val id: Int, val iso: String, val nameRu: String, val nameEn: String, val fields: Map<String, Any>)
data class City(val id: Int, val nameRu: String, val nameEn: String, val lat: Double, val lon: Double, val fields: Map<String, Any>)
data class DbInfo(val version: String, val created: Instant, val type: String, val charset: Charset, val hasCities: Boolean, val ipBlocks: Int)
```

Notes:

- With the country database (`SxGeo.dat`), `lookup` fills only `country.id` and `country.iso`; `region` and `city` are `null`.
- When only the country is known for an IP range, `city` and `region` are `null`, and `country` has its name and coordinates.
- The country always comes from the city record (`country_id`), so `lookup` and `country` never disagree. When a city has no region, or the database wrongly links the region to another country, `country` is the full record of that country from the database (with names and coordinates). Only if the database has no such record does `country` have just `id` and `iso`.
- `name(locale)` uses only the given locale, never the default one: pass `Locale.getDefault()` or the request locale yourself.
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

### IP strings

- Dotted-decimal IPv4: exactly four octets 0–255 of 1–3 ASCII digits without leading zeros. Like PHP `ip2long`, `"08.8.8.8"` and `"010.0.0.1"` are rejected.
- The IPv4-mapped IPv6 form `::ffff:1.2.3.4` is accepted (the prefix in any case), the dotted part follows the same rules. Other IPv6 forms (`::1.2.3.4`, `0:0:0:0:0:ffff:…`) are not.
- Spaces, `\t`, `\r` and `\n` around the address are ignored; other whitespace is not.
- `host:port`, host names, `"77.88.8.8, 10.0.0.1"` and anything else give `null`. Do not pass a raw `X-Forwarded-For` header, see [client IP behind a proxy](#client-ip-behind-a-proxy).

### Exceptions

- `IllegalArgumentException` from the constructors if the data is not a Sypex Geo 2.2 database of the supported version, is corrupted (header, indexes, record formats) or truncated. The `Path` and `File` constructors start the message with the file name; see [what the errors mean](#what-do-the-constructor-errors-mean).
- `IOException` from the `Path`, `File` and `InputStream` constructors if the data cannot be read.
- `IllegalStateException` (`"Corrupted Sypex Geo database: ..."`) from `country`, `countryId` and `lookup` if a database record they need is corrupted: it points outside its section or runs past its end. This does not happen with an intact database.
- `country()` and `countryId()` decode only the country id of the record, so a corrupted record is reported by `lookup()` (`IllegalStateException`) but may not be by `country()`.

A malformed IP never causes an exception; the result is simply `null`.

## Recipes

### Spring Boot

One instance for the whole application, the path from `application.properties` (`sypexgeo.path=/var/lib/geo/SxGeoCity.dat`):

```kotlin
import io.github.row2002.sypexgeo.SxGeo
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path

@Configuration
class GeoConfig {
    @Bean
    fun sxGeo(@Value("\${sypexgeo.path}") path: Path) = SxGeo(path) // a broken file stops the startup
}
```

With periodic reloading (`@EnableScheduling` on the application class), the old database stays in use if the new file is broken:

```kotlin
import io.github.row2002.sypexgeo.GeoInfo
import io.github.row2002.sypexgeo.SxGeo
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.atomic.AtomicReference

@Service
class GeoService(@Value("\${sypexgeo.path}") private val path: Path) {
    private val log = LoggerFactory.getLogger(GeoService::class.java)
    private val geo = AtomicReference(SxGeo(path))
    @Volatile private var loaded: FileTime = Files.getLastModifiedTime(path)

    fun country(ip: String?): String? = geo.get().country(ip)
    fun lookup(ip: String?): GeoInfo? = geo.get().lookup(ip)

    @Scheduled(fixedDelay = 10 * 60 * 1000) // every 10 minutes
    fun reloadIfChanged() {
        val modified = Files.getLastModifiedTime(path)
        if (modified == loaded) return
        try {
            geo.set(SxGeo(path))
        } catch (e: Exception) { // IllegalArgumentException (not a valid database) or IOException
            log.warn("Keeping the old Sypex Geo database: {}", e.message)
        }
        loaded = modified
    }
}
```

### Client IP behind a proxy

Do not parse `X-Forwarded-For` yourself: any client can send that header, and its first value is whatever the client wrote, so taking it lets anyone pick their country. Let the server replace the remote address with the client address from the headers of your **trusted** proxies only, and use `request.getRemoteAddr()` (in Ktor, `call.request.origin.remoteAddress`):

- Spring Boot on Tomcat: `server.forward-headers-strategy=native`. This is Tomcat's `RemoteIpValve`: it trusts only known proxies (private network addresses by default, set `server.tomcat.remoteip.internal-proxies` for others) and reads `X-Forwarded-For` from right to left. Avoid `framework` (`ForwardedHeaderFilter`) and Jetty's `ForwardedRequestCustomizer` unless your proxy overwrites `X-Forwarded-For`: they take its first, client-supplied value.
- Standalone Tomcat: `<Valve className="org.apache.catalina.valves.RemoteIpValve" />` in `server.xml`.
- Ktor: the `XForwardedHeaders` plugin (dependency `io.ktor:ktor-server-forwarded-header`), configured for your proxy chain: `install(XForwardedHeaders) { useLastProxy() }` for exactly one proxy that appends to `X-Forwarded-For`, `skipLastProxies(N - 1)` for N such proxies.

Only enable this when the application is reachable through your proxies alone; otherwise clients can spoof their address.

### Servlet filter

```kotlin
import io.github.row2002.sypexgeo.SxGeo
import jakarta.servlet.FilterChain // javax.servlet.* for Servlet 4 / Spring Boot 2
import jakarta.servlet.http.HttpFilter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

/** Puts the visitor's country code ("RU" or null) into the "country" request attribute. */
class CountryFilter(private val geo: SxGeo) : HttpFilter() {
    override fun doFilter(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        request.setAttribute("country", geo.country(request.remoteAddr)) // remoteAddr: see "Client IP behind a proxy"
        chain.doFilter(request, response)
    }
}
```

In Spring Boot, declaring the filter as a bean is enough: `@Bean fun countryFilter(geo: SxGeo) = CountryFilter(geo)` applies it to all requests. To set URL patterns or the order, return a `FilterRegistrationBean<CountryFilter>` instead.

### Ktor

The `XForwardedHeaders` plugin from the `io.ktor:ktor-server-forwarded-header` dependency takes the client address from the proxy headers; configure it for your proxy chain (see [client IP behind a proxy](#client-ip-behind-a-proxy)):

```kotlin
import io.github.row2002.sypexgeo.SxGeo
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.forwardedheaders.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File

fun Application.geoModule() {
    val geo = SxGeo(File("SxGeoCity.dat"))
    install(XForwardedHeaders) { useLastProxy() } // exactly one proxy that appends; N proxies: skipLastProxies(N - 1)
    routing {
        get("/country") {
            call.respondText(geo.country(call.request.origin.remoteAddress) ?: "unknown")
        }
    }
}
```

### Reloading the database without a restart

Instances are immutable, so swapping a reference is enough. Validate the new file before it replaces the old one: download and unzip it next to the database (the same file system), open it, then move it in place with `ATOMIC_MOVE`:

```kotlin
import io.github.row2002.sypexgeo.SxGeo
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.atomic.AtomicReference

class GeoHolder(private val path: Path) {
    private val current = AtomicReference(SxGeo(path))

    fun get(): SxGeo = current.get()

    /** [downloaded] is the new .dat file in the same directory as [path]. */
    fun install(downloaded: Path) {
        val fresh = SxGeo(downloaded) // throws if the file is broken: nothing changes
        Files.move(downloaded, path, ATOMIC_MOVE, REPLACE_EXISTING)
        current.set(fresh)
    }
}
```

The same with a cron job, so the application only re-opens the file (see [Spring Boot](#spring-boot)); `mv` within one file system is atomic:

```bash
cd /var/lib/geo && curl -fsSL --retry 5 -o city.zip https://sypexgeo.net/files/SxGeoCity_utf8.zip &&
  unzip -o city.zip SxGeoCity.dat -d new && mv -f new/SxGeoCity.dat SxGeoCity.dat
```

### Android

Put the database into `src/main/assets/` and open it once (for example in `Application.onCreate`, off the main thread):

```kotlin
import android.content.Context
import io.github.row2002.sypexgeo.SxGeo

fun openGeo(context: Context): SxGeo = context.assets.open("SxGeo.dat").use(::SxGeo)
```

- The API uses `java.time.Instant` (`DbInfo.created`) and `java.nio.file.Path` (a constructor): use `minSdk 26`, or core library desugaring for older devices and avoid the `Path` constructor there.
- Prefer the country database `SxGeo.dat` on mobile (~1 MB instead of a few tens of MB in memory and in the APK).
- The library is not tested on devices; reports are welcome.

## FAQ

### Does it support IPv6?

No. The Sypex Geo format has IPv4 data only; IPv6 addresses (except the `::ffff:1.2.3.4` form of an IPv4 address) return `null`. If you need IPv6, use MaxMind GeoLite2 or GeoIP2.

### Which database should I choose?

`SxGeo.dat` if you need the country only: it is ~1 MB instead of a few tens of MB, and every call (`country()`, `countryId()`, `lookup()`) takes ~0.2 µs. `SxGeoCity.dat` for regions and cities with Russian and English names: `lookup()` on it takes ~2 µs, `country()` and `countryId()` ~0.4 µs (see [Performance](#performance)). `SxGeoMax.dat` (paid) adds fields such as time zones.

### How often are the databases updated?

Regularly: check the date of the latest build on the [download page](https://sypexgeo.net/ru/download/), and the date of the loaded file with `info.created`. A new database can be picked up without a restart, see [the recipe](#reloading-the-database-without-a-restart).

### What are `AP`, `EU`, `A1`, `O1`?

Special codes in the Sypex Geo country table, inherited from the legacy GeoIP country list: `AP` the Asia/Pacific region and `EU` Europe, without a specific country; `A1` an anonymous proxy; `O1` other country. They are not ISO 3166-1 country codes: handle them yourself before looking up a country name or showing a flag. `XK` (Kosovo) is a user-assigned ISO 3166-1 code, and the JDK knows it.

### How do I get country names with `SxGeo.dat`?

The country database has ISO codes only; take the names from the JDK:

```kotlin
val iso = geo.country("77.88.8.8") ?: return
val country = Locale("", iso) // on JDK 19+: Locale.of("", iso)
country.getDisplayCountry(Locale("ru"))   // "Россия"
country.getDisplayCountry(Locale.ENGLISH) // "Russia"
```

For `AP`, `A1` and `O1` `getDisplayCountry` returns the code itself, and for `EU` it returns `"European Union"`, which is not what Sypex Geo means (Europe): handle these four codes yourself. On JDK 19+ create the locale with `Locale.of("", iso)`: the `new Locale("", iso)` constructor (`Locale("", iso)` in Kotlin) works on JDK 8+ but is deprecated since JDK 19.

### What do the constructor errors mean?

`IllegalArgumentException` messages (the `Path` and `File` constructors prefix them with the file name):

| Message | What to do |
|---|---|
| `Not a Sypex Geo database: this is a ZIP archive - unzip it and pass the .dat file` | the downloaded `.zip` was passed; unzip it |
| `Not a Sypex Geo database: looks like an HTML page - the download probably failed` | the download returned an error page; check the URL and `curl -f` |
| `Not a Sypex Geo database: this is a MaxMind .mmdb file` | a MaxMind database; this library reads Sypex Geo `.dat` files only |
| `Not a Sypex Geo database: file is empty or too short (N bytes)` | an interrupted download or a wrong file |
| `Not a Sypex Geo database` | some other file |
| `Unsupported Sypex Geo database version …` | only format 2.2 is supported (all current databases) |
| `Truncated Sypex Geo database` | the file is incomplete; download it again |
| `Corrupted Sypex Geo database …`, `Invalid pack format …`, `Unknown database charset …` | the file is damaged; download it again |

## Sypex Geo vs MaxMind GeoLite2

| | Sypex Geo | MaxMind GeoLite2 |
|---|---|---|
| Getting the database | direct download, no account | free account, licence key, GeoLite2 EULA |
| IPv6 | no | yes |
| Other data | City Max: time zones and more | ASN database, time zones, postal codes |
| Strengths | Russia and CIS, Russian names of all countries, regions and cities | worldwide coverage, many languages |
| Java/Kotlin library | sypexgeo-java (no dependencies) or sypexgeo-kotlin (`kotlin-stdlib` only) | `com.maxmind.geoip2:geoip2` (with Jackson) |

## Performance

Measured on JDK 21, a cloud VM with an Intel Xeon 2.8 GHz (4 vCPU), one thread, a simple timing loop (not JMH): 1,000,000 random IPv4 strings from `1.0.0.0`–`223.255.255.255`, each method called once per string in 8 passes, the first 4 of them warm-up; `SxGeo.dat` from January 2025 and a December 2014 `SxGeoCity.dat` (1.7 million ranges):

| Database | Method | Time per call |
|---|---|---|
| `SxGeo.dat` | `country()`, `countryId()`, `lookup()` | ~0.2 µs |
| `SxGeoCity.dat` | `lookup()` | ~2 µs |
| `SxGeoCity.dat` | `country()`, `countryId()` | ~0.35 µs |

`country()` and `countryId()` on a city database read only the country id of the record, without decoding the names. Opening a database takes ~0.05 s (`SxGeo.dat`) to ~0.1 s (`SxGeoCity.dat`); it is kept in memory, which takes about the file size.

## Differences from SxGeo.php

- An IP that is exactly the first address of a range resolves to that range. `SxGeo.php` 2.2.3 sometimes answers with the neighbouring range. Version 1.0.0 of this library still had the `SxGeo.php` behaviour; fixed in 1.1.0.
- The country in `lookup` comes from the city record (see [API](#api)), not from the region as in `getCityFull()`.
- A corrupted database causes an exception (see [Exceptions](#exceptions)) instead of PHP warnings and garbage results.
- `::ffff:1.2.3.4` is accepted; `ip2long` in PHP rejects it.

Limitations: IPv4 only; only format 2.2, which covers all current databases.

## Building and testing

```bash
./gradlew build
```

The main tests build synthetic databases themselves and check the answers at every range boundary against a brute-force search, so they need no downloads. The integration tests use the real databases: put `SxGeo.dat` and `SxGeoCity.dat` into `testdata/`, otherwise those tests are skipped. CI downloads the databases automatically. Changes are listed in [CHANGELOG.md](CHANGELOG.md), the release procedure is in [RELEASING.md](RELEASING.md).

## License and data

The library is under the [MIT](LICENSE) license. The algorithm is based on the official `SxGeo.php` (© zapimir, BINOVATOR, BSD license).

The library ships no geolocation data. The Sypex Geo databases are © BINOVATOR, [sypexgeo.net](https://sypexgeo.net/), and are distributed under the terms published there: check them before you redistribute `.dat` files (for example inside an application, an APK or a Docker image).
