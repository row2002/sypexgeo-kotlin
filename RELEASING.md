# Releasing

The library is published in two places:

- **JitPack** as `com.github.row2002:sypexgeo-kotlin:<tag>`. Nothing to set up: JitPack builds a tag on the first request (`jitpack.yml`, `./gradlew publishToMavenLocal` with `JITPACK=true`, which selects the `com.github.row2002` group).
- **Maven Central** as `io.github.row2002:sypexgeo-kotlin:<version>`, by `.github/workflows/release.yml` with the [vanniktech maven-publish plugin](https://vanniktech.github.io/gradle-maven-publish-plugin/central/). Prepared, not published yet: it needs the one-time setup below. Until then the workflow skips this step with a notice.

## One-time setup for Maven Central

1. Create an account at [central.sonatype.com](https://central.sonatype.com/) (sign in with GitHub as `row2002` is simplest).
2. Namespaces → add `io.github.row2002`. With a GitHub login it is verified automatically; otherwise create the temporary public repository `row2002/<verification key>` that the portal asks for, press *Verify*, then delete the repository.
3. Account → *Generate User Token*: a username and a password for publishing (not the account password).
4. Create a GPG key for signing and publish its public part:

   ```bash
   gpg --quick-gen-key "row2002 <your e-mail>" rsa4096 sign 0   # remember the passphrase
   gpg --list-keys                                               # the key id is the last 16 hex digits
   gpg --keyserver keyserver.ubuntu.com --send-keys <key id>
   gpg --armor --export-secret-keys <key id> > signing-key.asc   # for the secret below, then delete the file
   ```

5. GitHub → repository Settings → Secrets and variables → Actions → *New repository secret*:

   | Secret | Value |
   |---|---|
   | `MAVEN_CENTRAL_USERNAME` | user token username |
   | `MAVEN_CENTRAL_PASSWORD` | user token password |
   | `SIGNING_KEY` | the whole content of `signing-key.asc` |
   | `SIGNING_KEY_PASSWORD` | the key passphrase |

The same setup works for sypexgeo-java (the same namespace and secrets, added to that repository too).

## Release procedure

1. Move the entries of `## [x.y.z] - Unreleased` in `CHANGELOG.md` under the new version with today's date, update the comparison links at the bottom, and set the version in the README installation examples and in the `?: "x.y.z"` default of `build.gradle.kts`.
2. Commit, then tag and push (the tag name is the version, as JitPack users write it):

   ```bash
   git tag 1.2.0
   git push origin main 1.2.0
   ```

3. The *Release* workflow builds the project, uploads the signed artifacts to the Central Portal (if the secrets are set) and creates a GitHub Release with the CHANGELOG section.
4. Maven Central: open [central.sonatype.com](https://central.sonatype.com/) → Deployments, check the deployment and press *Publish* (to skip this step, change `publishToMavenCentral` to `publishAndReleaseToMavenCentral` in the workflow). The artifacts appear on Maven Central within about 30 minutes. Only after the first successful publication add the `io.github.row2002` coordinates to the READMEs.
5. JitPack: open `https://jitpack.io/#row2002/sypexgeo-kotlin/<tag>` once to trigger the build and check its log.

To check the publication locally: `./gradlew publishToMavenLocal` puts `io.github.row2002:sypexgeo-kotlin` into `~/.m2`, `JITPACK=true ./gradlew publishToMavenLocal` the JitPack coordinates `com.github.row2002:sypexgeo-kotlin`. Signing is enabled only when the `signingInMemoryKey` Gradle property is set.
