# Building DocSafe

DocSafe builds **reproducibly**: building the same git tag with the documented toolchain
produces a **byte-for-byte identical** release APK (verified — two clean builds yield the same
SHA-256). This is what lets anyone confirm that a released binary was built from this source.
See [VERIFYING.md](VERIFYING.md) for how to check that.

## Toolchain

Use exactly these versions. They are pinned in the repo (Gradle wrapper + version catalog), so
you do not choose them manually — but your local JDK and Android SDK must match.

| Component | Version | Where it's pinned |
| --- | --- | --- |
| JDK | **17** (Temurin 17 recommended) | your environment |
| Gradle | **8.11.1** | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | **8.9.1** | `gradle/libs.versions.toml` |
| Kotlin | **2.0.21** | `gradle/libs.versions.toml` |
| KSP | **2.0.21-1.0.28** | `gradle/libs.versions.toml` |
| compileSdk / targetSdk | **36** | `app/build.gradle.kts` |
| minSdk | **26** | `app/build.gradle.kts` |
| SDK Build-Tools | **35.0.0** (AGP 8.9.1 default) | resolved by AGP |

> The project targets JVM bytecode 17. JDK 17 is the canonical build JDK; Android Studio's
> bundled JBR 21 also produces identical output. For verification, prefer **Temurin JDK 17** so
> everyone uses the same compiler.

Install the SDK pieces with `sdkmanager`:

```bash
sdkmanager "platforms;android-36" "build-tools;35.0.0" "platform-tools"
```

## Build

```bash
git clone https://github.com/dmitryduka/docsafe.git
cd docsafe
git checkout v<version>            # e.g. the tag matching the Play Store release

# Run the unit tests (crypto + vault format + merge + concurrency)
./gradlew :core:crypto:test :core:vault:test

# Build the release APK
./gradlew :app:assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

If you do not have signing keys configured (see below), the release APK is built **unsigned** —
that is fine and expected for verification.

## What makes the build reproducible

- **Pinned toolchain** — Gradle (wrapper), AGP, Kotlin, KSP are fixed; all dependencies are
  locked to exact versions in `gradle/libs.versions.toml`.
- **Fixed identity** — `versionCode` / `versionName` are constants in `app/build.gradle.kts`;
  nothing reads the date, a random value, or the build path into the APK.
- **No embedded dependency metadata** — `android.dependenciesInfo { includeInApk = false;
  includeInBundle = false }` disables the encrypted, Play-signed dependency blob AGP adds by
  default (it is non-deterministic and would break verification).
- **Deterministic shrinking** — R8 output is stable for identical inputs.

The only thing that differs between two people's builds is the **signature** (each signs with a
different key). Verification therefore compares the APK *contents*, ignoring the signature block
— see [VERIFYING.md](VERIFYING.md).

## Release signing (maintainer only)

Release builds are signed from a local `keystore.properties` + a `.jks` keystore that are
**git-ignored and never committed**. Create your own:

```bash
keytool -genkeypair -v -keystore docsafe-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 -alias docsafe
```

`keystore.properties` (project root):

```properties
storeFile=docsafe-release.jks
storePassword=********
keyAlias=docsafe
keyPassword=********
```

Then:

```bash
./gradlew :app:assembleRelease   # signed APK  (publish this on GitHub Releases)
./gradlew :app:bundleRelease     # AAB         (upload this to Google Play)
```
