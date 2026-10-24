import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    kotlin("jvm") version "2.1.0"
    `java-library`
    `maven-publish`
}

group = "com.github.row2002"
version = findProperty("version")?.takeIf { it != "unspecified" } ?: "1.0.0" // JitPack passes -Pversion=<tag>

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    withSourcesJar()
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

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
