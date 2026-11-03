# Connecso Aether Client

Android client for Connecso's Aether route engine. It provides a full-device
VPN, per-app split tunneling, Cloudflare/WARP transport, optional second-hop
destinations from subscriptions, a live connection notification, and in-app
release updates.

## Download

Get the Android package from the [latest release](https://github.com/SocalledTaha/Connecso-Aether-Client/releases/latest). Release `0.1.0` currently includes the **arm64-v8a** APK for most current phones.

Each release includes `SHA256SUMS` to verify the downloaded APK.

## Build

Use Android Studio with JDK 17, Android SDK/NDK configured by the Gradle build,
Rust, Go, and the repository's native setup scripts. Build native dependencies
before assembling the APK; see `.github/workflows/ci.yml` for the complete
build sequence.

The app and this derivative are distributed under [AGPL-3.0](LICENSE). This
repository retains the upstream notices and third-party license details in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
