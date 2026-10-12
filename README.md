# sol-mesh

Decentralized, serverless peer-to-peer messaging and direct file transfer for Android.
No central server: peers find each other on the local network (UDP multicast) or over the
internet (Kademlia DHT), authenticate with Ed25519 identities, and talk over end-to-end
encrypted (X25519 + Double Ratchet + AES-256-GCM) connections.

See [ARCHITECTURE.md](ARCHITECTURE.md), [NETWORK_PROTOCOL.md](NETWORK_PROTOCOL.md),
[CRYPTOGRAPHY.md](CRYPTOGRAPHY.md), [SECURITY.md](SECURITY.md) and [TESTING.md](TESTING.md).

## Build

- Locally: open the project in Android Studio and run the `app` configuration, or run
  `gradle :app:assembleDebug` (JDK 17, Gradle 9.3.1, Android SDK 36).
- Debug builds expect a `debug.keystore` in the project root (git-ignored). Create one with:
  `keytool -genkeypair -keystore debug.keystore -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug"`
- On GitHub: the workflow in `.github/workflows/android.yml` builds the debug APK and
  publishes it as a build artifact.

## Tests

`gradle :app:testDebugUnitTest`
