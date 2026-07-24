# vload

[![API](https://img.shields.io/badge/API-21%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=21)
[![Releases](https://img.shields.io/github/v/release/alaaabd90/vload)](https://github.com/alaaabd90/vload/releases)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-orange.svg)](https://www.gnu.org/licenses/gpl-3.0)

sing-box / universal proxy toolchain for Android, forked from [NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid), with an added **dual-network weighted load balancing** feature.

## What's different from NekoBox

vload lets you combine two physical networks (WiFi + SIM1, WiFi + SIM2, or SIM1 + SIM2) into a single VPN session:

* Assign a server profile to each network (the same profile can be used for both).
* Set a relative speed weight per network (e.g. 70/30).
* New connections through the VPN are distributed across both networks according to that weight, so multi-connection downloaders (e.g. IDM) see combined throughput from both links.
* If one network drops mid-session, traffic automatically reroutes to the surviving network.

This is connection-level load balancing (not single-stream link bonding), which matches how parallel-connection downloaders already work.

## Downloads

[GitHub Releases](https://github.com/alaaabd90/vload/releases)

## Supported Proxy Protocols

* SOCKS (4/4a/5)
* HTTP(S)
* SSH
* Shadowsocks
* VMess
* Trojan
* VLESS
* AnyTLS
* ShadowTLS
* TUIC
* Hysteria 1/2
* WireGuard
* Trojan-Go (trojan-go-plugin)
* NaïveProxy (naive-plugin)
* Mieru (mieru-plugin)

## Supported Subscription Formats

* Some widely used formats (like Shadowsocks, ClashMeta and v2rayN)
* sing-box outbound

Only resolving outbound (i.e. nodes) is supported. Routing rules etc. in a subscription are ignored.

## Credits

This project is a fork of [MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid).

Core:

- [SagerNet/sing-box](https://github.com/SagerNet/sing-box)

Android GUI:

- [shadowsocks/shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android)
- [SagerNet/SagerNet](https://github.com/SagerNet/SagerNet)

Web Dashboard:

- [Yacd-meta](https://github.com/MetaCubeX/Yacd-meta)
