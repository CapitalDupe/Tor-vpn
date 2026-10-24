# Tor Proxy Chain VPN (Android)

A per-device VPN app that routes all traffic through a chain you choose:

`apps → tun → hev-socks5-tunnel → local SOCKS5 chain relay (127.0.0.1:1080) → [Tor via Orbot] → proxy 1 → proxy 2 → … → destination`

You pick how many hops to use and their order (including none, or Tor only).

## Architecture

- **MainActivity** – add / reorder / remove proxy hops, toggle Tor, connect / disconnect, live status.
- **ProxyConfig.kt** – hop data model + persistence (SharedPreferences JSON).
- **ProxyChainSocksServer.kt** – local SOCKS5 server that performs the hop-by-hop CONNECT
  negotiation (SOCKS5, HTTP CONNECT, HTTPS CONNECT with certificate + hostname checks).
- **OrbotManager.kt** – starts Tor through [Orbot](https://orbot.app)'s intent API and waits for its
  `ON` status broadcast. No Tor binary is bundled.
- **TorVpnService.kt** – owns the `VpnService` tun interface, the relay, and the tun2socks bridge.
- **hev/htproxy/TProxyService.kt** – JNI shim for hev-socks5-tunnel.

## Required: the tun2socks library

Turning raw IP packets into SOCKS5 connections is done by
[hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel), used as a JNI **library**
(it is not an executable and cannot be launched with `ProcessBuilder`).

- **CI**: the GitHub Actions workflow in `.github/workflows/build.yml` builds it with the NDK and
  produces a debug APK as the `app-debug-apk` artifact. Nothing to do.
- **Local build, option A (no NDK)**: put a prebuilt `hev-socks5-tunnel.aar` (all ABIs) from that
  project's releases/CI into `app/libs/`. Its natives register as `hev.htproxy.TProxyService`,
  which matches the shim in this repo.
- **Local build, option B**: follow the Android section of the hev-socks5-tunnel README
  (`ndk-build`) and copy the resulting `libhev-socks5-tunnel.so` files to
  `app/src/main/jniLibs/<abi>/`. Keep the default package/class name.

Without the library the app builds, but Connect shows an error telling you it is missing.

## Using Tor

1. Install [Orbot](https://orbot.app).
2. Turn on **Route through Tor first**. Tor runs on your phone (127.0.0.1:9050), so it is always
   the **first** hop: `phone → Tor → proxy 1 → proxy 2 → destination`. A remote proxy can't connect
   to a Tor instance on your phone, so Tor can't be placed after your proxies.
3. Connect. The app asks Orbot to start Tor and waits (up to 90 s) until it reports ready.
   Orbot itself is excluded from the VPN so its own traffic doesn't loop.

## Behaviour notes

- **DNS** is answered by hev's `mapdns`; real name resolution happens at the far end of the chain,
  so lookups don't leak.
- **UDP is not relayed** (only TCP CONNECT). QUIC and other UDP will fail and apps fall back to TCP.
- **IPv6** traffic is captured by the tunnel (no bypass) and relayed over TCP like IPv4.
- Proxy credentials are stored in plain SharedPreferences (app-private). Consider
  `EncryptedSharedPreferences` before distributing.
- If the service is killed, traffic is no longer protected. Consider enabling Android's
  *Always-on VPN* + *Block connections without VPN* for a kill-switch.
- Free public proxy lists are unreliable and often malicious; the preset button is intentionally a stub.

## Building

Open the folder in Android Studio (Koala+), let Gradle sync (Gradle 8.7 / AGP 8.5, JDK 17),
add the library as above, and run. Min SDK 26, target SDK 34.
