# Spatial Wall Android starter

This open-source starter installs the full Spatial Wall Android app from your PC over your local network. It does not fetch an app from an external server.

1. Open Spatial Wall on your PC and enable the phone remote.
2. Register this Android device using a fresh QR code or six-digit code from the PC. Invitations expire after 60 seconds, so an earlier QR may have expired while you installed the starter. Enable Remote permission for the registration.
3. The starter downloads the app from that PC over authenticated TLS, checks its SHA-256, package name, version and signing certificate, and opens Android's installation confirmation.
4. Allow installation from Spatial Wall when Android asks. Open Spatial Wall after the update. Your PC registration carries over.

The official starter and the full app have the same package name and signing certificate. The starter has a lower version number, so it cannot replace an already installed full app. Android 11 or later and a 64-bit ARM device are required.

The starter's source is MIT-licensed. BoringSSL is built from the pinned, unmodified revision in `src/core/remote_pairing/boringssl.json`; its notices and the Android NDK runtime notices are included in the APK and available from **Open-source licenses**. These terms permit use in the proprietary Spatial Wall product. The full app's end-user license is separate.

The public source export also includes the Gradle wrapper for building, under Apache-2.0; its original license is retained in `gradle-wrapper-LICENSE.txt` and the wrapper JAR. Gradle is not bundled in the APK.

## Build from source

The public source export preserves repository-relative paths. From the root of that source directory:

1. Install JDK 17 or later, Python 3, Git, Android SDK platform 36.1, NDK 28.2.13676358 and SDK CMake 3.22.1. Accept the Android SDK licenses.
2. Set `ANDROID_SDK_ROOT`. Create `android/local.properties` with `sdk.dir=<your SDK path>` using Java properties escaping. Copy this SDK setting into `android/installer/local.properties`.
3. Run `python scripts/setup-remote-pairing.py` to fetch and verify the fixed BoringSSL source.
4. Run `android/gradlew -p android/installer assembleDebug testDebugUnitTest` (`gradlew.bat` on Windows).

Locally built APKs use your own debug signing key and cannot update an official installation. The vendor's private signing key is not part of the source export. A developer release key can be supplied through `nyan.keystore`, `nyan.keystore.password` and `nyan.keystore.alias` in the ignored `android/local.properties`; use an absolute keystore path. Never commit these settings or keys.

Within the full development repository, use `scripts/build-android.ps1 -Installer -Release -Publish` on Windows or `scripts/build-android.sh --installer --publish` on Linux/macOS. Publishing requires the product signing certificate and writes only `dist/android-installer/spatialwall-installer.apk`.

The build and public-source export share `android/installer/shared-java.txt`. Registration, protected credential storage and APK verification use the full app's canonical sources, rather than separate implementations.
Their regression tests are shared through `shared-tests.txt`; JUnit 4.13.2 is a test-only dependency and is not included in the APK.

[日本語](README.ja.md)
