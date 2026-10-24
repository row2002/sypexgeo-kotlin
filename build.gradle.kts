import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.dokka") version "2.0.0"
    id("com.vanniktech.maven.publish") version "0.37.0"
    `java-library`
}

// JitPack (sets JITPACK=true) serves com.github.row2002:sypexgeo-kotlin:<tag>; Maven Central uses io.github.row2002
group = if (System.getenv("JITPACK") == "true") "com.github.row2002" else "io.github.row2002"
version = findProperty("version")?.takeIf { it != "unspecified" } ?: "1.1.0" // the release workflow and JitPack pass -Pversion=<tag>

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

// the -javadoc jar (built by the publish plugin): the Dokka task's other output, dokka-configuration.json, holds absolute build paths
tasks.withType<org.gradle.jvm.tasks.Jar>().matching { it.name == "dokkaJavadocJar" }.configureEach {
    exclude("dokka-configuration.json")
}

tasks.test {
    useJUnitPlatform()
    inputs.files(fileTree("testdata")).withPropertyName("testdata") // real databases, optional
    // -PtestJavaVersion=8 runs the tests on another installed JDK (CI checks JDK 8 and 11; Gradle itself needs 17+)
    findProperty("testJavaVersion")?.let { version ->
        javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(version.toString()) }
    }
}

mavenPublishing {
    // sources jar, and the Dokka HTML documentation as the -javadoc jar (there are no Java sources)
    configure(KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"), sourcesJar = true))
    coordinates(group.toString(), "sypexgeo-kotlin", version.toString())
    // ./gradlew publishToMavenCentral: credentials in mavenCentralUsername/mavenCentralPassword, see RELEASING.md
    publishToMavenCentral()
    // signing is required by Maven Central only; JitPack and publishToMavenLocal work without a key (or with a blank one)
    if (!providers.gradleProperty("signingInMemoryKey").orNull.isNullOrBlank()) signAllPublications()
    pom {
        name = "sypexgeo-kotlin"
        description = "Kotlin reader for Sypex Geo 2.2 databases: country, region and city by IPv4 address"
        inceptionYear = "2026"
        url = "https://github.com/row2002/sypexgeo-kotlin"
        licenses {
            license {
                name = "MIT License"
                url = "https://opensource.org/licenses/MIT"
                distribution = "repo"
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
