import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    kotlin("jvm") version "2.1.0"
    `java-library`
    id("com.vanniktech.maven.publish") version "0.35.0"
}

group = "io.github.row2002"
version = findProperty("version")?.takeIf { it != "unspecified" } ?: "1.0.0" // CI and JitPack pass -Pversion=<tag>

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_1_8
        // readable by Kotlin 1.9+ consumers
        languageVersion = KotlinVersion.KOTLIN_1_9
        apiVersion = KotlinVersion.KOTLIN_1_9
        freeCompilerArgs.add("-Xjdk-release=1.8")
    }
}

tasks.test {
    useJUnitPlatform()
}

mavenPublishing {
    publishToMavenCentral()
    // only when a key is given (CI), so publishToMavenLocal works without one
    if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
    pom {
        name = "sypexgeo-kotlin"
        description = "Kotlin reader for Sypex Geo IP geolocation databases (SxGeo.dat, SxGeoCity.dat) — offline, no dependencies"
        url = "https://github.com/row2002/sypexgeo-kotlin"
        licenses {
            license {
                name = "MIT License"
                url = "https://opensource.org/licenses/MIT"
            }
        }
        developers {
            developer {
                id = "row2002"
                name = "aevseev"
                url = "https://github.com/row2002"
            }
        }
        scm {
            url = "https://github.com/row2002/sypexgeo-kotlin"
            connection = "scm:git:https://github.com/row2002/sypexgeo-kotlin.git"
            developerConnection = "scm:git:ssh://git@github.com/row2002/sypexgeo-kotlin.git"
        }
    }
}
