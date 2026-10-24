# Releasing

A release is a pushed tag. The [release workflow](.github/workflows/release.yml) builds and tests it, publishes it to
Maven Central as `io.github.row2002:sypexgeo-kotlin:<version>` with the
[vanniktech maven-publish plugin](https://vanniktech.github.io/gradle-maven-publish-plugin/central/) and creates a
GitHub release with the tag's section of `CHANGELOG.md` as the release notes and the jars (main, sources, javadoc)
attached. Without the secrets of the one-time setup below, the workflow skips the Maven Central publication with a
notice and still creates the GitHub release.

JitPack is not advertised any more, but it still builds a tag on request as `com.github.row2002:sypexgeo-kotlin:<tag>`
(`jitpack.yml`; JitPack sets `JITPACK=true`, which selects the `com.github.row2002` group in `build.gradle.kts`).
Version 1.0.0 is available only there.

## One-time setup for Maven Central

The steps below can be done by hand or with a helper script; either way the result is the four repository secrets
of step 4.

1. Sign in to the [Central Portal](https://central.sonatype.com/) with the `row2002` GitHub account. The namespace
   `io.github.row2002` is then verified automatically; otherwise add it under *Namespaces*, create the temporary public
   repository `row2002/<verification key>` that the portal asks for, press *Verify*, then delete the repository.
2. *Account* → *Generate User Token*: a username and a password for publishing (not the account password).
3. Create a GPG key for signing and publish its public part:

   ```bash
   gpg --quick-gen-key "row2002 <your e-mail>" rsa4096 sign 0   # remember the passphrase
   gpg --list-keys                                               # the key id is the last 16 hex digits
   gpg --keyserver keyserver.ubuntu.com --send-keys <key id>
   gpg --armor --export-secret-keys <key id> > signing-key.asc   # for the secret below, then delete the file
   ```

4. Add the repository secrets (*Settings* → *Secrets and variables* → *Actions* → *New repository secret*, or
   `gh secret set <name>`, e.g. `gh secret set SIGNING_KEY < signing-key.asc`):

   | Secret | Value |
   |---|---|
   | `MAVEN_CENTRAL_USERNAME` | user token username |
   | `MAVEN_CENTRAL_PASSWORD` | user token password |
   | `SIGNING_KEY` | the whole content of `signing-key.asc` |
   | `SIGNING_KEY_PASSWORD` | the key passphrase |

   They reach Gradle as the `mavenCentralUsername`, `mavenCentralPassword`, `signingInMemoryKey` and
   `signingInMemoryKeyPassword` properties. For a manual release put them into `~/.gradle/gradle.properties` and run
   `./gradlew publishAndReleaseToMavenCentral -Pversion=<version>`.

The same namespace, token and key serve [sypexgeo-java](https://github.com/row2002/sypexgeo-java): add the same
secrets to that repository too.

## Release procedure

1. In `CHANGELOG.md`, rename `## [x.y.z] - Unreleased` to `## [x.y.z] - YYYY-MM-DD` and update the comparison
   links at the bottom. The release workflow takes the release notes from the section with exactly this heading.
2. Set the version `x.y.z` in the quick start and installation examples of `README.md` and `README.en.md` and in the
   `?: "x.y.z"` default of `build.gradle.kts` (used when no `-Pversion` is given).
3. Commit, then tag and push. The tag is the version (a `v` prefix also works and is dropped from the published
   version):

   ```bash
   git tag x.y.z
   git push origin main x.y.z
   ```

4. Check the *Release* workflow run. It builds and tests the tag, runs `publishAndReleaseToMavenCentral` if the
   secrets are set (the Central Portal releases the deployment by itself once it passes validation; nothing to press
   there, its state is under *Deployments*) and creates the GitHub release. The version shows up on Maven Central
   some minutes after the deployment is released.
5. Release the same version of [sypexgeo-java](https://github.com/row2002/sypexgeo-java) the same way, keeping
   the versions of the two libraries in step.

## Checking the publication locally

```bash
JITPACK=true ./gradlew publishToMavenLocal   # ~/.m2/repository/com/github/row2002/sypexgeo-kotlin/
./gradlew publishToMavenLocal                # ~/.m2/repository/io/github/row2002/sypexgeo-kotlin/
```

Artifacts are signed only when the `signingInMemoryKey` Gradle property is set and not blank.
