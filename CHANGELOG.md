# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [Semantic Versioning](https://semver.org/).

## [1.1.0] - Unreleased

### Fixed

- An IP equal to the first address of a range (or of a main index entry) resolves to that range; `SxGeo.php` 2.2.3 and 1.0.0 sometimes returned the neighbouring range.
- An empty first-byte group, or a first-byte group whose only block does not start at `X.0.0.0`, no longer resolves to the next block.
- Lookups never read outside the block table or the main index, even for unusual index sizes.
- The truncation check covers the country records, so every truncated file is rejected by the constructor.

### Changed

- Strict IPv4 parsing, the same in sypexgeo-java: only ASCII spaces, `\t`, `\r`, `\n` around the address are ignored; octets with leading zeros (`08.8.8.8`, `010.0.0.1`) are rejected like PHP `ip2long`.
- Invalid databases are detected by the constructors (`IllegalArgumentException`): pack formats, the first-byte index, truncation. A corrupted record makes `country`, `countryId` and `lookup` throw `IllegalStateException` ("Corrupted Sypex Geo database: ...") instead of `ArrayIndexOutOfBoundsException` or garbage.
- `IllegalArgumentException` messages say what the data looks like instead (a ZIP archive, an HTML page, a MaxMind `.mmdb` file, an empty or too short file); the `Path` and `File` constructors start them with the file name.
- When a city has no region, or its region belongs to another country, `lookup` returns the full country record (names, coordinates, fields) instead of only `id` and `iso`.
- The `ByteArray` constructor copies the array.
- `country()` and `countryId()` on city databases read only the country id of the record: ~2.5 times faster on `SxGeoCity.dat`.
- The published POM and Gradle module depend on `kotlin-stdlib` 1.9.25, so Kotlin 1.9 projects can use the library.
- The jar has `Automatic-Module-Name: io.github.row2002.sypexgeo.kotlin`; the `-javadoc` jar contains the Dokka HTML documentation; the POM has license, SCM and developer information.

### Added

- `SxGeo(InputStream)`: reads the stream to its end and does not close it (Android assets, classpath resources).
- `lookup(InetAddress)`, `country(InetAddress)`, `countryId(InetAddress)`, using the address bytes; `null` for an `Inet6Address`.
- IP strings in the IPv4-mapped IPv6 form `::ffff:1.2.3.4` (the prefix in any case).
- `name(Locale)` on `Country`, `Region` and `City`.
- Maven Central publishing setup (`io.github.row2002:sypexgeo-kotlin`, not published yet); JitPack coordinates `com.github.row2002:sypexgeo-kotlin` are unchanged.
- README: quick start, installation for Gradle (Kotlin/Groovy, version catalog) and Maven, recipes (Spring Boot, client IP behind a proxy, servlet filter, Ktor, reloading, Android), FAQ, measured performance.
- Hermetic tests on synthetic databases built by the tests, checked at every range boundary.

## [1.0.0] - 2026-10-24

### Added

- First release: reading Sypex Geo 2.2 databases (`SxGeo.dat`, `SxGeoCity.dat`, `SxGeoMax.dat`), `country`, `countryId`, `lookup`, `info`.

[1.1.0]: https://github.com/row2002/sypexgeo-kotlin/compare/1.0.0...HEAD
[1.0.0]: https://github.com/row2002/sypexgeo-kotlin/releases/tag/1.0.0
