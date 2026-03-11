# HBM Nuclear Tech Mod - 1.12.2 Custom Fork

> [!WARNING]
> **EXPERIMENTAL / 実験段階**
> This is a personal experimental fork. Many features are still under active development and may contain bugs.
> Issues may be reported, but responses and fixes cannot be guaranteed in a timely manner.
>
> これは個人的な実験用フォークです。多くの機能はまだ開発中でバグが含まれる可能性があります。
> Issueを開いていただいても、対応には時間がかかる、または対応できない場合があります。

## Fork Chain / フォークチェーン

- **[hima1379](https://github.com/hima1379/Hbm-s-Nuclear-Tech-GIT)** (this repository) — SM-6 missile system, SPY-1/SPY-6 radar, FCS Console additions
- [Alcater / NTM-Extended](https://github.com/Alcatergit/Hbm-s-Nuclear-Tech-GIT) — Custom 1.12.2 version
- [Drillgon200](https://github.com/Drillgon200/Hbm-s-Nuclear-Tech-GIT) — 1.12.2 port
- [HBMTheBobcat](https://github.com/HbmMods/Hbm-s-Nuclear-Tech-GIT) — original mod

## Known Issues / 既知の問題

- SM-6 missile defense system is experimental (intercept accuracy under improvement)
- SPY-1 / SPY-6 radar systems have basic functionality confirmed, but some features are untested
- FCS Console multi-target tracking may have edge cases
- **Issues may not be addressed promptly. This project is maintained on a best-effort basis.**

SM-6ミサイル防衛システムは実験段階（迎撃精度は改善中）です。
SPY-1/SPY-6レーダー系は基本動作確認済みですが、未テストの機能があります。
Issueへの対応は保証できません。

## Added Features / 追加機能

- **SM-6 (RIM-174 ERAM)** — Active radar homing surface-to-air missile with proximity fuse and CPA detonation logic
- **SPY-1 Radar** — Long-range phased array radar for SM-6 midcourse guidance
- **SPY-6 Radar** — Advanced phased array radar (AMDR) for SM-6 midcourse guidance
- **FCS Console** — Fire Control System console for managing radar tracks and missile launches
- **Launch Pad** — SM-6 capable launch platform

## 3D Model Credits / 3Dモデルクレジット

This project uses 3D models from the following creators. All rights belong to the original creators.
以下の制作者様の3Dモデルを使用しています。著作権は各制作者様に帰属します。

| Model / モデル | Creator / 制作者 | Source / 出典 | Notes / 備考 |
|---------------|-----------------|---------------|-------------|
| SM-6 missile | **burt_cocaine** | [PlanetMinecraft](https://www.planetminecraft.com/member/burt_cocaine/) | Original model partially modified / 原モデルを一部改変して使用 |
| SPG62 | **WTigerTw** | [Sketchfab](https://sketchfab.com/WTigerTw) | SPG62 model only / SPG62モデルのみ使用 |
| New Fusion Reactor | **HbmMods** | [GitHub](https://github.com/HbmMods/Hbm-s-Nuclear-Tech-GIT) | Original NTM mod model / 原作NTMモデルを使用 |

## Build Guide / ビルド方法

Download the repository, then open a shell prompt in that folder:

```
.\gradlew build
```

The compiled jar will be in `build\libs`.

## License / ライセンス

This project is licensed under the **GNU General Public License v3.0** (GPL-3.0), inherited from the upstream project.
See [LICENSE](LICENSE) and [LICENSE.LESSER](LICENSE.LESSER) for details.

Copyright (C) 2025 hima1379
Based on HBM's Nuclear Tech by HBMTheBobcat and contributors.

## Upstream Credits / 上流クレジット

- **HBMMods / The Bobcat** — original mod creator
- **Drillgon200** — 1.12.2 port
- **TheOriginalGolem** — 1.12.2 bug fixing
- **Alcater** — custom 1.12.2 version, textures
- Sten89, GB_Doge_9000, Hoboy, Doctor17, mexikoedi, Crowbar Collective, 70000HP, and other contributors
