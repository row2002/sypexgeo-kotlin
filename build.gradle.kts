import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.dokka") version "2.0.0"
    `java-library`
    `maven-publish`
}

group = "com.github.row2002"
version = findProperty("version")?.takeIf { it != "unspecified" } ?: "1.1.0" // JitPack passes -Pversion=<tag>

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    withSourcesJar()
    withJavadocJar()
}

kotlin {
    // readable by Kotlin 1.9+ consumers: 1.9 metadata and a published kotlin-stdlib dependency a 1.9 compiler can read
    coreLibrariesVersion = "1.9.25"
    compilerOptions {
        jvmTarget = JvmTarget.JVM_1_8
        languageVersion = KotlinVersion.KOTLIN_1_9
        apiVersion = KotlinVersion.KOTLIN_1_9
        freeCompilerArgs.add("-Xjdk-release=1.8")
    }
}

dokka {
    dokkaSourceSets.configureEach {
        sourceLink {
            localDirectory = file("src/main/kotlin")
            remoteUrl("https://github.com/row2002/sypexgeo-kotlin/blob/main/src/main/kotlin")
        }
    }
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "io.github.row2002.sypexgeo.kotlin")
    }
}

// there are no Java sources, so the -javadoc jar carries the Dokka HTML documentation
tasks.named<Jar>("javadocJar") {
    // only the HTML directory: the task's other output, dokka-configuration.json, holds absolute build paths
    from(tasks.named<org.jetbrains.dokka.gradle.tasks.DokkaGeneratePublicationTask>("dokkaGeneratePublicationHtml").flatMap { it.outputDirectory })
}

tasks.test {
    useJUnitPlatform()
    inputs.files(fileTree("testdata")).withPropertyName("testdata") // real databases, optional
    // -PtestJavaVersion=8 runs the tests on another installed JDK (CI checks JDK 8 and 11; Gradle itself needs 17+)
    findProperty("testJavaVersion")?.let { version ->
        javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(version.toString()) }
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name = "sypexgeo-kotlin"
                description = "Kotlin reader for Sypex Geo 2.2 databases: country, region and city by IPv4 address"
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
    }
}
