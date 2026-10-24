# sypexgeo-kotlin

**Русский** | [English](README.en.md)

Библиотека на Kotlin для чтения баз [Sypex Geo](https://sypexgeo.net/) 2.2 (`SxGeo.dat`, `SxGeoCity.dat`, `SxGeoMax.dat`) — определение страны, региона и города по IPv4-адресу.

- без зависимостей, кроме `kotlin-stdlib`;
- работает локально с файлом базы, без сетевых запросов и лимитов;
- база целиком загружается в память, поиск — несколько микросекунд;
- экземпляр неизменяемый и потокобезопасный — создайте один на всё приложение;
- JVM 8+ и Kotlin 1.9+, удобно использовать и из Java;
- поддерживаются UTF-8, CP1251 и Latin-1 версии баз.

Порт официального `SxGeo.php` 2.2.3 с исправленным поиском: IP, совпадающий с началом диапазона, определяется правильно (см. [Отличия от SxGeo.php](#отличия-от-sxgeophp)). Поиск проверяется тестами на каждой границе диапазонов синтетических баз и на настоящих базах.

## Установка

Библиотека публикуется через [JitPack](https://jitpack.io/#row2002/sypexgeo-kotlin).

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

## Где взять базу

Скачайте базу на [sypexgeo.net/ru/download](https://sypexgeo.net/ru/download/) и распакуйте архив:

| Файл | Что внутри | Размер |
|---|---|---|
| `SxGeo.dat` ([SxGeoCountry.zip](https://sypexgeo.net/files/SxGeoCountry.zip)) | только страны | ~0,8 МБ |
| `SxGeoCity.dat` ([SxGeoCity_utf8.zip](https://sypexgeo.net/files/SxGeoCity_utf8.zip)) | страны, регионы, города | ~40 МБ |
| `SxGeoMax.dat` (платная City Max) | то же + дополнительные поля (часовые пояса и др.) | — |

Базы обновляются регулярно — перекачивайте их хотя бы раз в месяц.

## Использование

```kotlin
import io.github.row2002.sypexgeo.SxGeo
import java.io.File

val geo = SxGeo(File("SxGeoCity.dat")) // один раз при старте приложения

geo.country("77.88.8.8")   // "RU"
geo.countryId("77.88.8.8") // 185

val info = geo.lookup("93.73.35.74")
if (info != null) {
    info.country.iso     // "UA"
    info.country.nameRu  // "Украина"
    info.region?.nameEn  // "Kyiv"
    info.region?.iso     // "UA-30"
    info.city?.nameRu    // "Киев"
    info.city?.nameEn    // "Kyiv"
    info.city?.lat       // 50.45466
    info.city?.lon       // 30.5238
}
```

Если IP нет в базе, он зарезервирован (`10.*`, `127.*`, `0.*`), записан неверно или равен `null`, методы возвращают `null` и не бросают исключений.

IP принимается только в десятичной записи IPv4: ровно четыре октета 0–255 из 1–3 цифр ASCII без ведущих нулей. Пробелы, `\t`, `\r` и `\n` по краям строки игнорируются. Как и PHP `ip2long`, библиотека отвергает `"08.8.8.8"` и `"010.0.0.1"`, а также IPv6, имена хостов и другие пробельные символы. Если IP берётся из заголовка вроде `X-Forwarded-For`, сначала выделите из него один адрес (первый или последний доверенный): строка вида `"77.88.8.8, 10.0.0.1"` целиком вернёт пустой результат.

### Из Java

```java
import io.github.row2002.sypexgeo.GeoInfo;
import io.github.row2002.sypexgeo.SxGeo;
import java.io.IOException;
import java.nio.file.Paths;

public class Main {
    public static void main(String[] args) throws IOException {
        SxGeo geo = new SxGeo(Paths.get("SxGeoCity.dat")); // IOException, если файл не прочитать

        String iso = geo.country("77.88.8.8"); // "RU" или null
        GeoInfo info = geo.lookup("77.88.8.8");
        if (info != null && info.getCity() != null) {
            System.out.println(info.getCity().getNameRu()); // "Москва"
        }
    }
}
```

Для чистого Java-проекта есть отдельная библиотека [sypexgeo-java](https://github.com/row2002/sypexgeo-java) с аналогичным API в стиле Java 17 (`Optional`, records).

### Другие способы открыть базу

```kotlin
SxGeo(Paths.get("/var/lib/geo/SxGeoCity.dat"))   // java.nio.file.Path
SxGeo(File("SxGeoCity.dat"))                     // java.io.File
SxGeo(javaClass.getResourceAsStream("/SxGeo.dat")!!.readBytes()) // из ресурсов или любого InputStream
```

Конструкторы бросают `IllegalArgumentException`, если данные не являются базой Sypex Geo 2.2, повреждены или обрезаны, а конструкторы с `Path` и `File` — ещё и `IOException`, если файл не удаётся прочитать. Конструктор с `ByteArray` копирует массив, так что его можно переиспользовать.

### Обновление базы без перезапуска

Экземпляр неизменяемый, так что достаточно подменить ссылку. Если новый файл битый, конструктор бросит исключение, и продолжит работать прежняя база:

```kotlin
@Volatile var geo = SxGeo(File("SxGeoCity.dat"))

fun reload() {
    geo = SxGeo(File("SxGeoCity.dat"))
}
```

## API

| Метод | Возвращает |
|---|---|
| `SxGeo(path: Path)`, `SxGeo(file: File)`, `SxGeo(data: ByteArray)` | открывает базу |
| `country(ip: String?): String?` | ISO-код страны (`"RU"`); работает с любой базой |
| `countryId(ip: String?): Int?` | id страны в Sypex Geo |
| `lookup(ip: String?): GeoInfo?` | страна, регион и город |
| `country(address: Inet4Address)`, `lookup(address: Inet4Address)` | то же для `Inet4Address` |
| `info: DbInfo` | дата сборки базы, тип, кодировка, число диапазонов |

```kotlin
data class GeoInfo(val country: Country, val region: Region?, val city: City?)
data class Country(val id: Int, val iso: String, val nameRu: String = "", val nameEn: String = "", val lat: Double = 0.0, val lon: Double = 0.0, val fields: Map<String, Any> = emptyMap())
data class Region(val id: Int, val iso: String, val nameRu: String, val nameEn: String, val fields: Map<String, Any>)
data class City(val id: Int, val nameRu: String, val nameEn: String, val lat: Double, val lon: Double, val fields: Map<String, Any>)
```

Особенности:

- С базой стран (`SxGeo.dat`) `lookup` заполняет только `country.id` и `country.iso`, а `region` и `city` равны `null`.
- Если для диапазона IP известна только страна, `city` и `region` равны `null`, а в `country` есть название и координаты.
- Страна всегда берётся из записи города (`country_id`), поэтому `lookup` и `country` не расходятся. Если у города нет региона или регион в базе ошибочно относится к другой стране, `country` — полная запись этой страны из базы (с названиями и координатами). Только если такой записи в базе нет, в `country` заполнены лишь `id` и `iso`.
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

### Исключения

- `IllegalArgumentException` — из конструкторов, если данные не являются базой Sypex Geo 2.2 нужной версии, повреждены (заголовок, индексы, форматы записей) или обрезаны.
- `IOException` — из конструкторов с `Path` и `File`, если файл не удаётся прочитать.
- `IllegalStateException` (`"Corrupted Sypex Geo database: ..."`) — из `country`, `countryId` и `lookup`, если нужная им запись базы повреждена: указывает за пределы своего раздела или выходит за его конец. С официальными базами этого не бывает.

Неверный IP никогда не приводит к исключению, результат просто `null`.

## Отличия от SxGeo.php

- IP, который ровно совпадает с началом диапазона, определяется правильно. В `SxGeo.php` 2.2.3 такой адрес иногда относится к соседнему диапазону (например, `1.4.0.0` — к Китаю вместо Австралии). Версия 1.0.0 этой библиотеки ещё повторяла поведение `SxGeo.php`, исправлено в 1.1.0.
- Страна в `lookup` берётся из записи города (см. [API](#api)), а не из региона, как в `getCityFull()`.
- Повреждённая база приводит к исключению (см. [Исключения](#исключения)), а не к предупреждениям PHP и мусору в ответе.

## Ограничения

- Только IPv4: формат Sypex Geo не содержит IPv6. Для IPv6-адреса методы вернут `null`.
- Поддерживается только формат 2.2, то есть все актуальные базы.
- База занимает в памяти столько же, сколько файл (~40 МБ для City).

## Сборка и тесты

```bash
./gradlew test
```

Основные тесты сами собирают синтетические базы и сверяют ответы на каждой границе диапазонов с перебором, им ничего не нужно скачивать. Интеграционные тесты используют настоящие базы: положите `SxGeo.dat` и `SxGeoCity.dat` в каталог `testdata/`, иначе эти тесты будут пропущены. В CI базы скачиваются автоматически.

## Лицензия

[MIT](LICENSE). Алгоритм основан на официальном `SxGeo.php` (© zapimir, BINOVATOR, лицензия BSD). Базы Sypex Geo распространяются на условиях, указанных на [sypexgeo.net](https://sypexgeo.net/).
