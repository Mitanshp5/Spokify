# Spokify

A native Android music player built with Kotlin, Jetpack Compose, Media3 and Room.
Includes a local library, playlists, listening history, song radio, lyrics, offline
downloads, and public Spotify/YouTube playlist imports.

## Install and update

Download [the latest APK](https://github.com/Mitanshp5/Spokify/releases/latest/download/spokify.apk)
on your phone and confirm installation in Android. An existing installation keeps
its library when updated with the same signing key.

Once this version is installed, open **Settings → App updates** to check,
download and install future releases without a USB connection. Android may ask
you to allow Spokify to install updates once; each installation still requires
your confirmation. Release metadata and APKs are public; the app needs no GitHub
credentials.

Spokify checks once on your first app open each calendar day. An available update
adds a small yellow dot to your profile image or initial. Tap it and choose
**Update Spokify**, below **Settings and privacy**. The result is remembered across
app restarts; **Check for updates** on the update screen works at any time.

## Build

Requires JDK 21, Android SDK platform 37, and an Android SDK path supplied through
`ANDROID_HOME` or the untracked `android/local.properties`. The extractor is
vendored in `android/third_party/LevyraExtractor` with its notices and license.

```sh
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assemblePerformance
```

On Windows use `gradlew.bat`. The performance variant uses R8 and resource
shrinking and is the package distributed to phones. Debug builds have additional
runtime overhead. The current update channel is signed with the original
developer key at `~/.android/debug.keystore`; back up that existing key securely.
A newly generated debug key cannot update existing installations. Signing keys,
SDK state, device data and local diagnostics are excluded from Git.

## Publish an update

1. Increase `spokify.versionCode` and `spokify.versionName` in
   `android/gradle.properties`; every APK release needs a higher version code.
2. Update `CHANGELOG.md`, run the tests, and build the performance APK.
3. Commit and push the reviewed source to `main`.
4. Prepare and publish the release using Python 3.10+, the Android SDK, Java and
   an authenticated GitHub CLI:

```sh
python scripts/release.py prepare
python scripts/release.py publish --directory android/.work/releases/v0.2.0
```

Use the new version's directory for later releases. Preparation verifies the APK
signature and creates `spokify.apk` and `spokify-update.json`. Publishing uploads
both to a draft release, verifies GitHub's uploaded SHA-256 digests, then makes
the release available. Existing releases are never overwritten. In the app,
downloads must match the manifest checksum, package, newer version and installed
signing key before Android's installer opens. Partial downloads can be retried.

See [the license](android/LICENSE) and [third-party notices](android/THIRD_PARTY_NOTICES.md).
