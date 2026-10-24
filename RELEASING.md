# Releasing

A release is a pushed tag. The library is published in two places:

- **JitPack** as `com.github.row2002:sypexgeo-kotlin:<tag>`. Nothing to set up: JitPack builds a tag on the first request
  (`jitpack.yml`; JitPack sets `JITPACK=true`, which selects the `com.github.row2002` group in `build.gradle.kts`).
- **Maven Central** as `io.github.row2002:sypexgeo-kotlin:<version>`, by the [release workflow](.github/workflows/release.yml)
  with the [vanniktech maven-publish plugin](https://vanniktech.github.io/gradle-maven-publish-plugin/central/).
  Prepared, not published yet: it needs the one-time setup below. Until the secrets are set, the workflow skips this
  step with a notice and still succeeds.

Either way, the release workflow then creates a GitHub release with the tag's section of `CHANGELOG.md` as the
release notes and the jars (main, sources, javadoc) attached.

## One-time setup for Maven Central

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

4. Add the repository secrets (*Settings* → *Secrets and variables* → *Actions* → *New repository secret*):

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
secrets to that repository too. After the first successful publication, add the Maven Central coordinates to the
"Installation" sections of `README.md` and `README.en.md`.

## Release procedure

1. In `CHANGELOG.md`, rename `## [x.y.z] - Unreleased` to `## [x.y.z] - YYYY-MM-DD` and update the comparison
   links at the bottom. The release workflow takes the release notes from the section with exactly this heading.
2. Set the version `x.y.z` in the installation examples of `README.md` and `README.en.md` and in the
   `?: "x.y.z"` default of `build.gradle.kts` (used when no `-Pversion` is given).
3. Commit, then tag and push. The tag is the version, as JitPack users write it (a `v` prefix also works and is
   dropped for Maven Central):

   ```bash
   git tag x.y.z
   git push origin main x.y.z
   ```

4. Check the *Release* workflow run. It builds and tests the tag, runs `publishAndReleaseToMavenCentral` if the
   secrets are set (the Central Portal releases the deployment by itself once it passes validation; nothing to press
   there, its state is under *Deployments*) and creates the GitHub release.
5. JitPack: open `https://jitpack.io/#row2002/sypexgeo-kotlin/x.y.z` once to trigger the build and check its log.
6. Release the same version of [sypexgeo-java](https://github.com/row2002/sypexgeo-java) the same way, keeping
   the versions of the two libraries in step.

## Checking the publication locally

```bash
JITPACK=true ./gradlew publishToMavenLocal   # ~/.m2/repository/com/github/row2002/sypexgeo-kotlin/
./gradlew publishToMavenLocal                # ~/.m2/repository/io/github/row2002/sypexgeo-kotlin/
```

Artifacts are signed only when the `signingInMemoryKey` Gradle property is set and not blank.
