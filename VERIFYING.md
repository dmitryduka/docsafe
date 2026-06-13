# Verifying that a release was built from this source

DocSafe stores sensitive documents **entirely on-device**. You don't have to take that on
trust: the app is open source and builds **reproducibly**, so anyone can confirm that a released
binary contains exactly this source and nothing else.

This guide shows how. There are two levels — pick whichever you need.

---

## TL;DR

1. Download the official **APK + `SHA256SUMS`** from the [GitHub Release](https://github.com/dmitryduka/docsafe/releases) for the version you want.
2. `git checkout` the matching **signed tag** and run `./gradlew :app:assembleRelease`.
3. Run `tools/verify-apk.sh <official.apk>` — it rebuilds-compares and tells you if the contents match.

If it reports a match, the released APK was built from this exact source.

---

## Why the comparison ignores the signature

Two clean builds of the same tag produce a **byte-for-byte identical** APK *except for the
cryptographic signature* — because you and the maintainer sign with different keys. So
verification compares the APK **contents** (code, resources, manifest, assets) and ignores the
signing block. That is the standard approach used by F-Droid and other reproducible-build
verifiers. (See [BUILDING.md](BUILDING.md) for why the rest of the build is deterministic.)

## Important: Google Play re-signs the app

Google Play uses **Play App Signing**. When the maintainer uploads an App Bundle (`.aab`), Google
*re-signs* it with a key only Google holds and generates per-device "split" APKs. As a result,
the APK that Play installs on your phone will **not** be byte-identical to a locally built APK,
and its signature is Google's, not the maintainer's.

So the **canonical artifact to verify is the APK published on GitHub Releases** (built by the
maintainer from the tagged source, signed with the maintainer's key). Each Play Store release is
cut from the **same signed git tag** as the corresponding GitHub Release. If you want to verify
what's running on your device specifically, see "Verifying the Play build" at the bottom.

---

## Level 1 — verify the GitHub Release APK (recommended)

### 1. Get the official APK and its hash

From the [Releases page](https://github.com/dmitryduka/docsafe/releases), download for your
version:

- `docsafe-<version>.apk`
- `SHA256SUMS`

Confirm the download matches the published hash, and (optionally) that the release asset is the
immutable one GitHub recorded:

```bash
shasum -a 256 -c SHA256SUMS
gh release verify-asset v<version> docsafe-<version>.apk   # optional, needs gh
```

### 2. Build from the matching tag

```bash
git clone https://github.com/dmitryduka/docsafe.git && cd docsafe
git checkout v<version>
./gradlew :app:assembleRelease    # produces an UNSIGNED release APK if you have no keys
```

### 3. Compare contents (ignoring the signature)

**Easiest — use the helper script in this repo:**

```bash
tools/verify-apk.sh /path/to/docsafe-<version>.apk
```

It unzips both APKs, drops the signature files, and diffs everything else. Output ends with
`MATCH ✅` or `DIFFERENCES FOUND ❌` plus the differing entries.

**Gold standard — `apksigcopier`** (copies the official signature onto your rebuild and checks
the result is byte-identical to the official APK):

```bash
pipx install apksigcopier        # or: pip install apksigcopier
apksigcopier compare /path/to/docsafe-<version>.apk \
    app/build/outputs/apk/release/app-release.apk --unsigned
# exit code 0 => identical
```

**Manual fallback** (no extra tools):

```bash
mkdir off rebuilt
unzip -q /path/to/docsafe-<version>.apk -d off
unzip -q app/build/outputs/apk/release/app-release.apk -d rebuilt
rm -rf off/META-INF/*.RSA off/META-INF/*.SF off/META-INF/*.MF \
       rebuilt/META-INF/*.RSA rebuilt/META-INF/*.SF rebuilt/META-INF/*.MF
diff -r off rebuilt && echo "MATCH"
```

A match means the released APK's code and resources are exactly what this source compiles to.

---

## Verifying the Play build (what's on your device)

You can't reproduce Play's re-signed split APKs byte-for-byte, but you can confirm two things:

1. **It's signed by the expected publisher.** Compare the signing certificate of the installed
   app to the one published on the Releases page / store listing:

   ```bash
   adb shell pm path app.docsafe                 # find base.apk path
   adb pull <path>/base.apk play-base.apk
   apksigner verify --print-certs play-base.apk  # check the SHA-256 cert digest
   ```

2. **It's the same version as a verified GitHub Release.** The version name/code shown in the
   app's settings and on the Play listing matches a tag whose GitHub APK you verified in Level 1.

For full content reproducibility, install the **GitHub Release APK** (verified above) instead of
the Play build — both are built from the same source.

---

## For maintainers: cutting a verifiable release

```bash
# 1. Tag and sign the release (requires your GPG key configured with git)
git tag -s v<version> -m "DocSafe v<version>"
git push origin v<version>

# 2. Build the signed APK and AAB from that exact checkout
./gradlew :app:assembleRelease :app:bundleRelease

# 3. Publish a GitHub Release with the APK + checksums (immutable)
APK=app/build/outputs/apk/release/app-release.apk
cp "$APK" docsafe-v<version>.apk
shasum -a 256 docsafe-v<version>.apk > SHA256SUMS
gh release create v<version> docsafe-v<version>.apk SHA256SUMS \
    --title "DocSafe v<version>" --notes "Reproducible build. Verify with VERIFYING.md."

# 4. Upload the .aab to Google Play (same tag, same source)
```

Sign your commits/tags (`git config commit.gpgsign true`, `git tag -s`) so the source itself is
provably authored by you.
