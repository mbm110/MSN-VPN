# MSN-VPN

<p align="center">
  <img src="app/src/main/res/drawable-nodpi/msnvpn_launcher.png" width="112" alt="MSN-VPN icon">
</p>

<p align="center">
  Native Android client for private, censorship-resistant connections.
</p>

<p align="center">
  <a href="https://github.com/mbm110/MSN-VPN/releases"><img src="https://img.shields.io/github/v/release/mbm110/MSN-VPN?display_name=tag&style=for-the-badge&color=74c69d" alt="Release"></a>
  <a href="https://github.com/mbm110/MSN-VPN/actions/workflows/build.yml"><img src="https://img.shields.io/github/actions/workflow/status/mbm110/MSN-VPN/build.yml?branch=master&style=for-the-badge&label=Android%20build" alt="Android build"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-AGPL--3.0-6c5ce7?style=for-the-badge" alt="AGPL-3.0"></a>
</p>

> **MSN-VPN** is an Android app built around the **Aether** network core — a private, censorship-resistant connection engine. It provides the native Android interface, VPN/TUN bridge, connection state, protocol picker, live logs, and release packaging.

## What MSN-VPN does

MSN-VPN turns Aether into an Android-first VPN experience. It provides the native interface, Android VPN/TUN bridge, connection state, protocol picker, live connection logs, and release packaging.

```text
Android UI + Android VPN/TUN
            │
            ▼
      MSN-VPN client
            │ JNI
            ▼
 Aether core — discovery, MASQUE, WireGuard, routing
```

## Highlights

- **Native Android UI** with one-tap connect, connection state, motion, and live logs.
- **Connection type picker**: **VPN** routes device traffic through Android `VpnService`; **Proxy** exposes local SOCKS5 at `127.0.0.1:1819` by default for apps configured to use it.
- **MASQUE** over HTTP/3, with HTTP/2 fallback when available.
- **WireGuard** for networks where it is reachable.
- **WARP-on-WARP** (`gool`) support through the Aether core.
- **Automatic endpoint scanning** with IP-level diagnostics, cached-gateway reconnect, and Ironclad verification.
- **Kill Switch** — a settings toggle that blocks all traffic if the tunnel drops unexpectedly, preventing any unencrypted leak until reconnection or manual disconnect.
- **Live IP & country** — after connecting, the main screen shows your public IP address and the country flag of the exit node.
- Retained Aether v1.3.0 Android FFI core builds into `libaether.so`.
- App-level default protocol setting and direct links to releases/source.

## Kill Switch

The Kill Switch is available in **Settings → Kill Switch** (above "Check for updates").

- **ON**: if the VPN tunnel drops for any reason other than your manual disconnect, the app immediately builds a *blocking* tunnel that routes all traffic (0.0.0.0/0) to a non-routable dummy address. No traffic can leak to the open internet. The service stays alive and reconnects automatically.
- **OFF**: traffic may fall back to the default network when the tunnel is down (standard Android behaviour).

Implementation is **Kotlin-only** — the native Rust core is untouched. The switch controls `VpnService.Builder` (bypass disabled when active) and intercepts unexpected disconnects inside the Kotlin `VpnService` wrapper.

## Protocol notes

| Protocol | Intended use |
| --- | --- |
| MASQUE | Recommended default. Uses HTTPS-like tunnel transport and can fall back to HTTP/2. |
| WireGuard | Fast direct transport where UDP/WireGuard is reachable. |
| WARP-on-WARP | Nested WireGuard transport supplied by Aether. It still needs a reachable outer WireGuard path. |

Network filtering differs by provider and location. A protocol appearing connected means Aether completed its tunnel readiness check; it does not promise that every destination is reachable on every network.

## Download

Pre-built debug APKs are available from [GitHub Releases](https://github.com/mbm110/MSN-VPN/releases).

| Device ABI | Asset |
| --- | --- |
| 64-bit ARM | `app-arm64-v8a-debug.apk` |
| 32-bit ARM | `app-armeabi-v7a-debug.apk` |

Install an APK from Android Downloads after allowing installs from the source application when Android asks.

## Build from source

### Requirements

- Android Studio with Android SDK 36
- Android NDK `26.3.11579264`
- CMake `3.22.1`
- JDK 17
- Rust stable with required Android targets:

  ```bash
  rustup target add aarch64-linux-android armv7-linux-androideabi
  ```

- `cargo-ndk` (optional — the included `core/build-android-linux.sh` drives the NDK clang toolchain directly)

### Build APKs

Gradle builds and stages the matching Aether library automatically:

```bash
# Both ABIs
./gradlew assembleDebug

# Single ABI
./gradlew assembleDebug -PtargetAbi=arm64-v8a
./gradlew assembleDebug -PtargetAbi=armeabi-v7a
```

APK output:

```text
app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk
```

### CI build

The [build workflow](.github/workflows/build.yml) runs on push and manually, building debug APKs for `arm64-v8a` and `armeabi-v7a`, then uploads them as a GitHub Actions artifact.

## Project layout

```text
app/                 Android application and JNI bridge
core/aether/         Aether Rust core used by this client
core/quiche/         QUIC/HTTP3 dependency used by Aether
.github/             build workflow
```

## Contributing

Read CONTRIBUTING.md before opening an issue or pull request.

## Security

Do not disclose security-sensitive tunnel, credential, or traffic issues in public issues.

## License

MSN-VPN is licensed under [GNU AGPL-3.0](LICENSE). Aether and bundled dependencies retain their own license terms; see their respective files in `core/`.

## Credits

- [Aether](https://github.com/CluvexStudio/Aether) — network core.
- [quiche](https://github.com/cloudflare/quiche) — QUIC and HTTP/3 library used by Aether.
- Built by [mbm110](https://github.com/mbm110).
