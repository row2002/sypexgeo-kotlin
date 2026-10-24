# sypexgeo-kotlin

[![CI](https://github.com/row2002/sypexgeo-kotlin/actions/workflows/ci.yml/badge.svg)](https://github.com/row2002/sypexgeo-kotlin/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.row2002/sypexgeo-kotlin)](https://central.sonatype.com/artifact/io.github.row2002/sypexgeo-kotlin)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

**Русский** | [English](README.en.md)

Библиотека на Kotlin для чтения баз [Sypex Geo](https://sypexgeo.net/) 2.2 (`SxGeo.dat`, `SxGeoCity.dat`, `SxGeoMax.dat`) — определение страны, региона и города по IPv4-адресу. JVM 8+, Kotlin 1.9+, Android, удобно использовать и из Java.

## Быстрый старт

1. Добавьте зависимость (`build.gradle.kts`):

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.row2002:sypexgeo-kotlin:1.1.0")
}
```

2. Скачайте базу:

```bash
curl -fLO https://sypexgeo.net/files/SxGeoCity_utf8.zip && unzip SxGeoCity_utf8.zip SxGeoCity.dat
```

3. Определите страну и город:

```kotlin
import io.github.row2002.sypexgeo.SxGeo
import java.io.File

val geo = SxGeo(File("SxGeoCity.dat"))           // один раз при старте; потокобезопасно
println(geo.country("77.88.8.8"))                // RU
println(geo.lookup("77.88.8.8")?.city?.nameRu)   // Москва
```

## Содержание

- [Какую библиотеку выбрать?](#какую-библиотеку-выбрать)
- [Возможности](#возможности)
- [Установка](#установка)
- [Где взять базу](#где-взять-базу)
- [Использование](#использование)
- [API](#api)
- [Рецепты](#рецепты): [Spring Boot](#spring-boot), [IP клиента за прокси](#ip-клиента-за-прокси), [фильтр сервлетов](#фильтр-сервлетов), [Ktor](#ktor), [обновление базы без перезапуска](#обновление-базы-без-перезапуска), [Android](#android)
- [Частые вопросы](#частые-вопросы)
- [Sypex Geo или MaxMind GeoLite2](#sypex-geo-или-maxmind-geolite2)
- [Производительность](#производительность)
- [Отличия от SxGeo.php](#отличия-от-sxgeophp)
- [Сборка и тесты](#сборка-и-тесты)
- [Лицензия и данные](#лицензия-и-данные)

## Какую библиотеку выбрать?

| Ваш проект | Библиотека |
|---|---|
| Java 17+ | [sypexgeo-java](https://github.com/row2002/sypexgeo-java): `Optional`, records, без зависимостей |
| Kotlin, Android, Java 8–16 | [sypexgeo-kotlin](https://github.com/row2002/sypexgeo-kotlin): nullable-типы, data-классы, JVM 8+, зависит только от `kotlin-stdlib` |
| Kotlin на Java 17+ | любая; у sypexgeo-java есть аннотации JSpecify, и Kotlin правильно видит, где может быть `null` |

Никогда не подключайте обе: у них одинаковые пакет и имена классов (`io.github.row2002.sypexgeo.SxGeo`). Поведение у них одинаковое, включая разбор IP и тексты ошибок.

## Возможности

- без зависимостей, кроме `kotlin-stdlib`;
- работает локально с файлом базы: без сетевых запросов, лимитов и регистрации;
- база целиком загружается в память, поиск города — около 2 мкс, страны — меньше 1 мкс (см. [Производительность](#производительность));
- экземпляр неизменяемый и потокобезопасный — создайте один на всё приложение;
- поддерживаются UTF-8, CP1251 и Latin-1 версии баз;
- порт официального `SxGeo.php` 2.2.3 с исправленным поиском: IP, совпадающий с началом диапазона, определяется правильно (см. [Отличия от SxGeo.php](#отличия-от-sxgeophp)). Каждая граница диапазонов проверяется в тестах на синтетических базах; тесты на настоящих базах проверяют случайные адреса.

## Установка

Библиотека публикуется в [Maven Central](https://central.sonatype.com/artifact/io.github.row2002/sypexgeo-kotlin).

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

Старая версия 1.0.0 опубликована только в JitPack: `com.github.row2002:sypexgeo-kotlin:1.0.0`.

## Где взять базу

Скачайте базу на [sypexgeo.net/ru/download](https://sypexgeo.net/ru/download/) и распакуйте архив:

| Файл | Что внутри | Размер |
|---|---|---|
| `SxGeo.dat` ([SxGeoCountry.zip](https://sypexgeo.net/files/SxGeoCountry.zip)) | только страны | ~1 МБ |
| `SxGeoCity.dat` ([SxGeoCity_utf8.zip](https://sypexgeo.net/files/SxGeoCity_utf8.zip)) | страны, регионы, города | несколько десятков МБ |
| `SxGeoMax.dat` (платная City Max) | то же + дополнительные поля (часовые пояса и др.) | — |

```bash
curl -fLO https://sypexgeo.net/files/SxGeoCountry.zip && unzip SxGeoCountry.zip SxGeo.dat
curl -fLO https://sypexgeo.net/files/SxGeoCity_utf8.zip && unzip SxGeoCity_utf8.zip SxGeoCity.dat
```

Базы регулярно обновляются: дату сборки показывают страница загрузки и `info.created`, а новую базу можно подхватить без перезапуска (см. [обновление базы без перезапуска](#обновление-базы-без-перезапуска)).

## Использование

```kotlin
import io.github.row2002.sypexgeo.SxGeo
import java.io.File
import java.util.Locale

val geo = SxGeo(File("SxGeoCity.dat")) // один раз при старте приложения

geo.country("77.88.8.8")   // "RU"
geo.countryId("77.88.8.8") // 185

val info = geo.lookup("93.73.35.74")
if (info != null) {
    info.country.iso                 // "UA"
    info.country.nameRu              // "Украина"
    info.country.name(Locale("ru"))  // "Украина"
    info.region?.nameEn              // "Kyiv"
    info.region?.iso                 // "UA-30"
    info.city?.nameRu                // "Киев"
    info.city?.name(Locale.ENGLISH)  // "Kiev"
    info.city?.lat                   // 50.45466
    info.city?.lon                   // 30.5238
}
```

Если IP нет в базе, он зарезервирован (`10.*`, `127.*`, `0.*`), записан неверно или равен `null`, методы возвращают `null` и не бросают исключений.

### Из Java

```java
import io.github.row2002.sypexgeo.GeoInfo;
import io.github.row2002.sypexgeo.SxGeo;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Locale;

public class Main {
    public static void main(String[] args) throws IOException {
        SxGeo geo = new SxGeo(Paths.get("SxGeoCity.dat")); // IOException, если файл не прочитать

        String iso = geo.country("77.88.8.8"); // "RU" или null
        GeoInfo info = geo.lookup("77.88.8.8");
        if (info != null && info.getCity() != null) {
            System.out.println(info.getCity().name(new Locale("ru"))); // "Москва"
        }
        System.out.println(geo.getInfo().getCreated()); // дата сборки базы
    }
}
```

Для проектов на Java 17+ есть [sypexgeo-java](https://github.com/row2002/sypexgeo-java) с тем же API в стиле Java (`Optional`, records), см. [Какую библиотеку выбрать?](#какую-библиотеку-выбрать).

### Другие способы открыть базу

```kotlin
SxGeo(Paths.get("/var/lib/geo/SxGeoCity.dat"))           // java.nio.file.Path
SxGeo(File("SxGeoCity.dat"))                             // java.io.File
javaClass.getResourceAsStream("/SxGeo.dat")!!.use(::SxGeo) // InputStream, например ресурс из classpath
SxGeo(File("SxGeo.dat").readBytes())                     // ByteArray
```

Конструктор с `InputStream` читает поток до конца и не закрывает его, поэтому нужен `use`. Конструктор с `ByteArray` копирует массив, так что его можно переиспользовать.

### InetAddress

```kotlin
import java.net.InetAddress

geo.country(InetAddress.getByName("77.88.8.8"))        // "RU"
geo.lookup(InetAddress.getByName("::ffff:77.88.8.8"))  // то же, что для 77.88.8.8
geo.countryId(InetAddress.getByName("2a02:6b8::feed")) // null: IPv6
```

`lookup`, `country` и `countryId` принимают и `InetAddress`; используются байты адреса, без преобразования в строку. Для `Inet6Address` результат `null` (JDK сам превращает `::ffff:a.b.c.d` в `Inet4Address`). Передавайте уже готовые адреса: `InetAddress.getByName` с именем хоста делает DNS-запрос.

## API

| Метод | Возвращает |
|---|---|
| `SxGeo(path: Path)`, `SxGeo(file: File)` | открывает файл базы; `IOException`, если файл не прочитать |
| `SxGeo(input: InputStream)` | читает поток до конца, не закрывает его |
| `SxGeo(data: ByteArray)` | читает базу из памяти; массив копируется |
| `country(ip: String?): String?` | ISO-код страны (`"RU"`); работает с любой базой |
| `countryId(ip: String?): Int?` | id страны в Sypex Geo |
| `lookup(ip: String?): GeoInfo?` | страна, регион и город |
| `country(address: InetAddress)`, `countryId(address: InetAddress)`, `lookup(address: InetAddress)` | то же для `InetAddress`; для IPv6 — `null` |
| `info: DbInfo` | дата сборки базы, тип, кодировка, число диапазонов |
| `name(locale: Locale): String` у `Country`, `Region`, `City` | `nameRu` для русского языка (`locale.language == "ru"`), если оно не пустое, иначе `nameEn`, а если пусто и оно — `nameRu` |

```kotlin
data class GeoInfo(val country: Country, val region: Region?, val city: City?)
data class Country(val id: Int, val iso: String, val nameRu: String = "", val nameEn: String = "", val lat: Double = 0.0, val lon: Double = 0.0, val fields: Map<String, Any> = emptyMap())
data class Region(val id: Int, val iso: String, val nameRu: String, val nameEn: String, val fields: Map<String, Any>)
data class City(val id: Int, val nameRu: String, val nameEn: String, val lat: Double, val lon: Double, val fields: Map<String, Any>)
data class DbInfo(val version: String, val created: Instant, val type: String, val charset: Charset, val hasCities: Boolean, val ipBlocks: Int)
```

Особенности:

- С базой стран (`SxGeo.dat`) `lookup` заполняет только `country.id` и `country.iso`, а `region` и `city` равны `null`.
- Если для диапазона IP известна только страна, `city` и `region` равны `null`, а в `country` есть название и координаты.
- Страна всегда берётся из записи города (`country_id`), поэтому `lookup` и `country` не расходятся. Если у города нет региона или регион в базе ошибочно относится к другой стране, `country` — полная запись этой страны из базы (с названиями и координатами). Только если такой записи в базе нет, в `country` заполнены лишь `id` и `iso`.
- `name(locale)` смотрит только на переданную локаль, никогда на локаль по умолчанию: передайте `Locale.getDefault()` или локаль запроса сами.
- `fields` содержит все поля записи, кроме внутренних ссылок (`region_seek`, `country_seek`, `country_id`). В City Max там есть и дополнительные поля, например `timezone`:

```kotlin
val tz = info.region?.fields?.get("timezone") as String? // только в City Max
```

### Сведения о базе

```kotlin
geo.info.created   // 2026-08-12T…Z
geo.info.type      // "SxGeo City EN"
geo.info.hasCities // true
```

### Строка с IP

- Десятичная запись IPv4: ровно четыре октета 0–255 из 1–3 цифр ASCII без ведущих нулей. Как и PHP `ip2long`, библиотека отвергает `"08.8.8.8"` и `"010.0.0.1"`.
- Принимается и IPv4-mapped IPv6 вида `::ffff:1.2.3.4` (префикс в любом регистре), адрес после префикса подчиняется тем же правилам. Другие формы IPv6 (`::1.2.3.4`, `0:0:0:0:0:ffff:…`) — нет.
- Пробелы, `\t`, `\r` и `\n` по краям строки игнорируются, другие пробельные символы — нет.
- `host:port`, имена хостов, `"77.88.8.8, 10.0.0.1"` и всё остальное дают `null`. Не передавайте заголовок `X-Forwarded-For` как есть, см. [IP клиента за прокси](#ip-клиента-за-прокси).

### Исключения

- `IllegalArgumentException` — из конструкторов, если данные не являются базой Sypex Geo 2.2 нужной версии, повреждены (заголовок, индексы, форматы записей) или обрезаны. Конструкторы с `Path` и `File` начинают сообщение с имени файла; см. [что означают ошибки](#что-означают-ошибки-конструктора).
- `IOException` — из конструкторов с `Path`, `File` и `InputStream`, если данные не удаётся прочитать.
- `IllegalStateException` (`"Corrupted Sypex Geo database: ..."`) — из `country`, `countryId` и `lookup`, если нужная им запись базы повреждена: указывает за пределы своего раздела или выходит за его конец. С исправной базой этого не происходит.
- `country()` и `countryId()` разбирают из записи только id страны, поэтому повреждённую запись обнаружит `lookup()` (`IllegalStateException`), а `country()` может и не обнаружить.

Неверный IP никогда не приводит к исключению, результат просто `null`.

## Рецепты

### Spring Boot

Один экземпляр на всё приложение, путь — из `application.properties` (`sypexgeo.path=/var/lib/geo/SxGeoCity.dat`):

```kotlin
import io.github.row2002.sypexgeo.SxGeo
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path

@Configuration
class GeoConfig {
    @Bean
    fun sxGeo(@Value("\${sypexgeo.path}") path: Path) = SxGeo(path) // с битым файлом приложение не запустится
}
```

С периодическим обновлением (`@EnableScheduling` на классе приложения); если новый файл битый, продолжает работать старая база:

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

    @Scheduled(fixedDelay = 10 * 60 * 1000) // каждые 10 минут
    fun reloadIfChanged() {
        val modified = Files.getLastModifiedTime(path)
        if (modified == loaded) return
        try {
            geo.set(SxGeo(path))
        } catch (e: Exception) { // IllegalArgumentException (не база Sypex Geo) или IOException
            log.warn("Keeping the old Sypex Geo database: {}", e.message)
        }
        loaded = modified
    }
}
```

### IP клиента за прокси

Не разбирайте `X-Forwarded-For` сами: этот заголовок может прислать любой клиент, и его первое значение — то, что написал клиент, так что кто угодно сможет выбрать себе страну. Пусть сервер подставит адрес клиента из заголовков только ваших **доверенных** прокси, а вы берите `request.getRemoteAddr()` (в Ktor — `call.request.origin.remoteAddress`):

- Spring Boot на Tomcat: `server.forward-headers-strategy=native`. Это `RemoteIpValve`: он доверяет только известным прокси (по умолчанию адресам частных сетей, другие задаются в `server.tomcat.remoteip.internal-proxies`) и читает `X-Forwarded-For` справа налево. Не используйте `framework` (`ForwardedHeaderFilter`) и `ForwardedRequestCustomizer` в Jetty, если ваш прокси не перезаписывает `X-Forwarded-For`: они берут его первое значение, то есть присланное клиентом.
- Отдельный Tomcat: `<Valve className="org.apache.catalina.valves.RemoteIpValve" />` в `server.xml`.
- Ktor: плагин `XForwardedHeaders` (зависимость `io.ktor:ktor-server-forwarded-header`), настроенный под вашу цепочку прокси: `install(XForwardedHeaders) { useLastProxy() }` для ровно одного прокси, который дописывает адрес в `X-Forwarded-For`, и `skipLastProxies(N - 1)` для N таких прокси.

Включайте это, только если приложение доступно исключительно через ваши прокси, иначе клиенты смогут подменить свой адрес.

### Фильтр сервлетов

```kotlin
import io.github.row2002.sypexgeo.SxGeo
import jakarta.servlet.FilterChain // javax.servlet.* для Servlet 4 / Spring Boot 2
import jakarta.servlet.http.HttpFilter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

/** Кладёт код страны посетителя ("RU" или null) в атрибут запроса "country". */
class CountryFilter(private val geo: SxGeo) : HttpFilter() {
    override fun doFilter(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        request.setAttribute("country", geo.country(request.remoteAddr)) // remoteAddr: см. «IP клиента за прокси»
        chain.doFilter(request, response)
    }
}
```

В Spring Boot достаточно объявить фильтр бином: `@Bean fun countryFilter(geo: SxGeo) = CountryFilter(geo)` подключит его ко всем запросам. Чтобы задать URL-шаблоны или порядок, верните вместо него `FilterRegistrationBean<CountryFilter>`.

### Ktor

Плагин `XForwardedHeaders` из зависимости `io.ktor:ktor-server-forwarded-header` подставляет адрес клиента из заголовков прокси; настройте его под свою цепочку прокси (см. [IP клиента за прокси](#ip-клиента-за-прокси)):

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
    install(XForwardedHeaders) { useLastProxy() } // ровно один прокси, дописывающий адрес; N прокси: skipLastProxies(N - 1)
    routing {
        get("/country") {
            call.respondText(geo.country(call.request.origin.remoteAddress) ?: "unknown")
        }
    }
}
```

### Обновление базы без перезапуска

Экземпляр неизменяемый, так что достаточно подменить ссылку. Проверьте новый файл, прежде чем он заменит старый: скачайте и распакуйте его рядом с базой (в той же файловой системе), откройте, затем переместите на место с `ATOMIC_MOVE`:

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

    /** [downloaded] — новый .dat-файл в том же каталоге, что и [path]. */
    fun install(downloaded: Path) {
        val fresh = SxGeo(downloaded) // для битого файла бросит исключение, и ничего не изменится
        Files.move(downloaded, path, ATOMIC_MOVE, REPLACE_EXISTING)
        current.set(fresh)
    }
}
```

То же через cron, а приложение только перечитывает файл (см. [Spring Boot](#spring-boot)); `mv` в пределах одной файловой системы атомарен:

```bash
cd /var/lib/geo && curl -fsSL --retry 5 -o city.zip https://sypexgeo.net/files/SxGeoCity_utf8.zip &&
  unzip -o city.zip SxGeoCity.dat -d new && mv -f new/SxGeoCity.dat SxGeoCity.dat
```

### Android

Положите базу в `src/main/assets/` и откройте один раз (например, в `Application.onCreate`, не в главном потоке):

```kotlin
import android.content.Context
import io.github.row2002.sypexgeo.SxGeo

fun openGeo(context: Context): SxGeo = context.assets.open("SxGeo.dat").use(::SxGeo)
```

- В API есть `java.time.Instant` (`DbInfo.created`) и `java.nio.file.Path` (конструктор): нужен `minSdk 26` либо core library desugaring для старых устройств, и там не используйте конструктор с `Path`.
- На мобильных лучше брать базу стран `SxGeo.dat` (~1 МБ вместо нескольких десятков МБ в памяти и в APK).
- На устройствах библиотека не тестировалась; сообщения о результатах приветствуются.

## Частые вопросы

### Поддерживается ли IPv6?

Нет. В формате Sypex Geo есть только данные IPv4; для IPv6-адресов (кроме записи IPv4-адреса вида `::ffff:1.2.3.4`) методы вернут `null`. Если нужен IPv6, используйте MaxMind GeoLite2 или GeoIP2.

### Какую базу выбрать?

`SxGeo.dat`, если нужна только страна: ~1 МБ вместо нескольких десятков МБ, и любой вызов (`country()`, `countryId()`, `lookup()`) занимает ~0,2 мкс. `SxGeoCity.dat` — для регионов и городов с названиями на русском и английском: `lookup()` на ней занимает ~2 мкс, `country()` и `countryId()` — ~0,4 мкс (см. [Производительность](#производительность)). `SxGeoMax.dat` (платная) добавляет поля вроде часовых поясов.

### Как часто обновляются базы?

Регулярно: дату последней сборки смотрите на [странице загрузки](https://sypexgeo.net/ru/download/), а дату загруженного файла — в `info.created`. Новую базу можно подхватить без перезапуска, см. [рецепт](#обновление-базы-без-перезапуска).

### Что такое `AP`, `EU`, `A1`, `O1`?

Специальные коды в таблице стран Sypex Geo, унаследованные из старого списка стран GeoIP: `AP` — Азиатско-Тихоокеанский регион и `EU` — Европа, без конкретной страны; `A1` — анонимный прокси; `O1` — другая страна. Это не коды стран ISO 3166-1: обработайте их сами, прежде чем искать название страны или показывать флаг. `XK` (Косово) — код из пользовательского диапазона ISO 3166-1, JDK его знает.

### Как получить названия стран с `SxGeo.dat`?

В базе стран есть только ISO-коды, названия можно взять из JDK:

```kotlin
val iso = geo.country("77.88.8.8") ?: return
val country = Locale("", iso) // на JDK 19+: Locale.of("", iso)
country.getDisplayCountry(Locale("ru"))   // "Россия"
country.getDisplayCountry(Locale.ENGLISH) // "Russia"
```

Для `AP`, `A1` и `O1` метод `getDisplayCountry` возвращает сам код, а для `EU` — «Европейский союз» (`"European Union"`), хотя Sypex Geo имеет в виду Европу: эти четыре кода обработайте сами. На JDK 19+ создавайте локаль через `Locale.of("", iso)`: конструктор `new Locale("", iso)` (в Kotlin — `Locale("", iso)`) работает на JDK 8+, но с JDK 19 устарел.

### Что означают ошибки конструктора?

Сообщения `IllegalArgumentException` (конструкторы с `Path` и `File` добавляют в начало имя файла):

| Сообщение | Что делать |
|---|---|
| `Not a Sypex Geo database: this is a ZIP archive - unzip it and pass the .dat file` | передан скачанный `.zip`; распакуйте его |
| `Not a Sypex Geo database: looks like an HTML page - the download probably failed` | вместо базы скачалась страница с ошибкой; проверьте URL и `curl -f` |
| `Not a Sypex Geo database: this is a MaxMind .mmdb file` | это база MaxMind; библиотека читает только `.dat` Sypex Geo |
| `Not a Sypex Geo database: file is empty or too short (N bytes)` | прерванная загрузка или не тот файл |
| `Not a Sypex Geo database` | какой-то другой файл |
| `Unsupported Sypex Geo database version …` | поддерживается только формат 2.2 (все актуальные базы) |
| `Truncated Sypex Geo database` | файл неполный; скачайте заново |
| `Corrupted Sypex Geo database …`, `Invalid pack format …`, `Unknown database charset …` | файл повреждён; скачайте заново |

## Sypex Geo или MaxMind GeoLite2

| | Sypex Geo | MaxMind GeoLite2 |
|---|---|---|
| Как получить базу | прямая ссылка, без регистрации | бесплатный аккаунт, лицензионный ключ, GeoLite2 EULA |
| IPv6 | нет | да |
| Другие данные | City Max: часовые пояса и др. | база ASN, часовые пояса, почтовые индексы |
| Сильные стороны | Россия и СНГ, русские названия всех стран, регионов и городов | весь мир, много языков |
| Библиотека для Java/Kotlin | sypexgeo-java (без зависимостей) или sypexgeo-kotlin (только `kotlin-stdlib`) | `com.maxmind.geoip2:geoip2` (с Jackson) |

## Производительность

Замеры на JDK 21, облачная ВМ с Intel Xeon 2,8 ГГц (4 vCPU), один поток, простой цикл с замером времени (не JMH): 1 000 000 случайных IPv4-строк из `1.0.0.0`–`223.255.255.255`, каждый метод вызывается по разу на строку в 8 проходах, из которых первые 4 — прогрев; `SxGeo.dat` от января 2025 года и `SxGeoCity.dat` от декабря 2014 года (1,7 млн диапазонов):

| База | Метод | Время на вызов |
|---|---|---|
| `SxGeo.dat` | `country()`, `countryId()`, `lookup()` | ~0,2 мкс |
| `SxGeoCity.dat` | `lookup()` | ~2 мкс |
| `SxGeoCity.dat` | `country()`, `countryId()` | ~0,35 мкс |

`country()` и `countryId()` на базе городов читают из записи только id страны, не разбирая названия. Открытие базы занимает ~0,05 с (`SxGeo.dat`) — ~0,1 с (`SxGeoCity.dat`); база хранится в памяти и занимает примерно размер файла.

## Отличия от SxGeo.php

- IP, который ровно совпадает с началом диапазона, определяется правильно. В `SxGeo.php` 2.2.3 такой адрес иногда относится к соседнему диапазону. Версия 1.0.0 этой библиотеки ещё повторяла поведение `SxGeo.php`, исправлено в 1.1.0.
- Страна в `lookup` берётся из записи города (см. [API](#api)), а не из региона, как в `getCityFull()`.
- Повреждённая база приводит к исключению (см. [Исключения](#исключения)), а не к предупреждениям PHP и мусору в ответе.
- Принимается `::ffff:1.2.3.4`; PHP `ip2long` такую строку отвергает.

Ограничения: только IPv4; только формат 2.2, то есть все актуальные базы.

## Сборка и тесты

```bash
./gradlew build
```

Основные тесты сами собирают синтетические базы и сверяют ответы на каждой границе диапазонов с перебором, им ничего не нужно скачивать. Интеграционные тесты используют настоящие базы: положите `SxGeo.dat` и `SxGeoCity.dat` в каталог `testdata/`, иначе эти тесты будут пропущены. В CI базы скачиваются автоматически. Изменения перечислены в [CHANGELOG.md](CHANGELOG.md), порядок выпуска релиза — в [RELEASING.md](RELEASING.md).

## Лицензия и данные

Библиотека распространяется по лицензии [MIT](LICENSE). Алгоритм основан на официальном `SxGeo.php` (© zapimir, BINOVATOR, лицензия BSD).

Библиотека не содержит геоданных. Базы Sypex Geo — © BINOVATOR, [sypexgeo.net](https://sypexgeo.net/), они распространяются на условиях, опубликованных там: проверьте их, прежде чем распространять `.dat`-файлы (например, внутри приложения, APK или Docker-образа).
