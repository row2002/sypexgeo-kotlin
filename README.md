# sypexgeo-kotlin

**Русский** | [English](README.en.md)

Библиотека на Kotlin для чтения баз [Sypex Geo](https://sypexgeo.net/) 2.2 (`SxGeo.dat`, `SxGeoCity.dat`, `SxGeoMax.dat`) — определение страны, региона и города по IPv4-адресу.

- без зависимостей, кроме `kotlin-stdlib`;
- работает локально с файлом базы, без сетевых запросов и лимитов;
- база целиком загружается в память, поиск — несколько микросекунд;
- экземпляр неизменяемый и потокобезопасный — создайте один на всё приложение;
- JVM 8+ и Kotlin 1.9+, удобно использовать и из Java;
- поддерживаются UTF-8, CP1251 и Latin-1 версии баз.

Порт официального `SxGeo.php` 2.2.3. Результаты сверены на 30 000 случайных IP с [pysyge](https://github.com/idlesign/pysyge) — независимым портом `SxGeo.php`.

## Установка

Библиотека опубликована в [Maven Central](https://central.sonatype.com/artifact/io.github.row2002/sypexgeo-kotlin).

**Gradle (Kotlin DSL)**

```kotlin
dependencies {
    implementation("io.github.row2002:sypexgeo-kotlin:1.0.1")
}
```

**Maven**

```xml
<dependency>
    <groupId>io.github.row2002</groupId>
    <artifactId>sypexgeo-kotlin</artifactId>
    <version>1.0.1</version>
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

Если IP нет в базе, он зарезервирован (`10.*`, `127.*`, `0.*`), записан неверно или равен `null`, методы возвращают `null` и не бросают исключений. Если IP берётся из заголовка вроде `X-Forwarded-For`, сначала выделите из него один адрес (первый или последний доверенный): строка вида `"77.88.8.8, 10.0.0.1"` целиком вернёт пустой результат.

### Из Java

```java
SxGeo geo = new SxGeo(Paths.get("SxGeoCity.dat"));

String iso = geo.country("77.88.8.8"); // "RU" или null
GeoInfo info = geo.lookup("77.88.8.8");
if (info != null && info.getCity() != null) {
    System.out.println(info.getCity().getNameRu()); // "Москва"
}
```

Для чистого Java-проекта есть отдельная библиотека [sypexgeo-java](https://github.com/row2002/sypexgeo-java) с аналогичным API в стиле Java 17 (`Optional`, records).

### Другие способы открыть базу

```kotlin
SxGeo(Paths.get("/var/lib/geo/SxGeoCity.dat"))   // java.nio.file.Path
SxGeo(File("SxGeoCity.dat"))                     // java.io.File
SxGeo(javaClass.getResourceAsStream("/SxGeo.dat")!!.readBytes()) // из ресурсов или любого InputStream
```

Конструктор бросает `IllegalArgumentException`, если файл не является базой Sypex Geo 2.2 или обрезан.

### Обновление базы без перезапуска

Экземпляр неизменяемый, так что достаточно подменить ссылку:

```kotlin
@Volatile var geo = SxGeo(File("SxGeoCity.dat"))

fun reload() {
    geo = SxGeo(File("SxGeoCity.dat"))
}
```

## API

| Метод | Возвращает |
|---|---|
| `country(ip: String): String?` | ISO-код страны (`"RU"`); работает с любой базой |
| `countryId(ip: String): Int?` | id страны в Sypex Geo |
| `lookup(ip: String): GeoInfo?` | страна, регион и город |
| `country(address: Inet4Address)`, `lookup(address: Inet4Address)` | то же для `Inet4Address` |
| `info: DbInfo` | дата сборки базы, тип, кодировка, число диапазонов |

```kotlin
data class GeoInfo(val country: Country, val region: Region?, val city: City?)
data class Country(val id: Int, val iso: String, val nameRu: String, val nameEn: String, val lat: Double, val lon: Double, val fields: Map<String, Any>)
data class Region(val id: Int, val iso: String, val nameRu: String, val nameEn: String, val fields: Map<String, Any>)
data class City(val id: Int, val nameRu: String, val nameEn: String, val lat: Double, val lon: Double, val fields: Map<String, Any>)
```

Особенности:

- С базой стран (`SxGeo.dat`) `lookup` заполняет только `country.id` и `country.iso`, а `region` и `city` равны `null`.
- Если для диапазона IP известна только страна, `city` и `region` равны `null`, а в `country` есть название и координаты.
- Если у города нет региона, `region == null`, а в `country` заполнены только `id` и `iso`. То же с `country` бывает, когда регион в базе ошибочно относится к другой стране: страна всегда берётся из записи города, поэтому `lookup` и `country` не расходятся.
- `fields` содержит все поля записи из базы. В City Max там есть и дополнительные поля, например `timezone`:

```kotlin
val tz = info.region?.fields?.get("timezone") as String? // только в City Max
```

### Сведения о базе

```kotlin
geo.info.created   // 2026-08-12T…Z
geo.info.type      // "SxGeo City EN"
geo.info.hasCities // true
```

## Ограничения

- Только IPv4: формат Sypex Geo не содержит IPv6. Для IPv6-адреса методы вернут `null`.
- Поддерживается только формат 2.2, то есть все актуальные базы.
- Как и в `SxGeo.php`, для IP, который ровно совпадает с началом диапазона, иногда возвращается соседний диапазон. Это особенность эталонного алгоритма, сохранённая ради совместимости.
- База занимает в памяти столько же, сколько файл (~40 МБ для City).

## Сборка и тесты

```bash
./gradlew test
```

Интеграционные тесты используют настоящие базы. Положите `SxGeo.dat` и `SxGeoCity.dat` в каталог `testdata/`, иначе эти тесты будут пропущены. В CI базы скачиваются автоматически.

## Лицензия

[MIT](LICENSE). Алгоритм основан на официальном `SxGeo.php` (© zapimir, BINOVATOR, лицензия BSD). Базы Sypex Geo распространяются на условиях, указанных на [sypexgeo.net](https://sypexgeo.net/).
