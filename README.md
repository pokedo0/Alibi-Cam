![Alibi, Witness every moment](readme_content/banner.webp)

# Alibi-Cam（摩托车/两轮车行车记录仪增强版）

<p float="left" align="center">
    <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/01.webp" width="30%" />
    <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/02.webp" width="30%" />
    <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/03.webp" width="30%" />
    <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/04.webp" width="30%" />
    <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/05.webp" width="30%" />
    <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/06.webp" width="30%" />
</p>

本项目为 [Myzel394/Alibi](https://github.com/Myzel394/Alibi) 的**二次 Fork 增强版**，专为**摩托车/两轮车骑行记录**与**取证**场景深度定制与优化。

Alibi 可以在后台持续循环录制音视频，并在你需要时一键保存最后一段时间的录像。除通过 GitHub Releases API 检查更新外，核心录像与取证功能完全离线运行，无需网络连接。

## 二次 Fork 增强特性（本项目）

- 🎥 **双摄像头同步录像** — 支持两个摄像头同时录制（前后双摄或后置多摄），并优化多物理镜头调度逻辑。实际可用性取决于手机厂商的相机 HAL 支持（vivo 手机通常需要更改包名后才可启用）。
- 🧭 **视频防抖配置** — 单摄像头可启用原生光学防抖（OIS）与 EIS；双摄像头场景防抖效果视厂商实现而定。
- 🚀 **快速开始录音/录像** — 支持桌面图标 App Shortcuts 快捷入口与下拉通知栏快捷磁贴（Quick Settings Tile），无需进入前台界面即可在后台一键启动录制。
- 🔄 **GitHub Releases 自动检查更新** — 移除第三方更新源，直连 GitHub 官方 Releases API 检查最新版本并提供一键跳转。
- 🛡️ **纯净安全** — 彻底清理遥测与分析上报逻辑，保障隐私。

## 一次 Fork 继承特性（Alibi-Leo）

- 🔧 **修复 FFmpeg 崩溃 bug** — 修复自动停止录制时因依赖缺失导致最后一分钟视频丢失的问题。
- ⚡ **默认 24fps 省电** — 从 30fps 降至 24fps，省电约 20%，骑行画面依然清晰流畅。
- 🛑 **姿态感知自动停止** — 支持基于手机姿态感知的自动停止录像，并支持灵敏度精细调节。
- 🗑️ **永久删除选项** — 可选择直接物理删除录像文件，绕过系统回收站，方便快速释放存储空间。

## Download / 下载

[<img src="readme_content/github-badge.webp" alt="Get it on GitHub" height="80">](https://github.com/pokedo0/Alibi-Cam/releases)

## 原始版本

[<img src="readme_content/google-play-badge.png" alt="Get it on Google Play" height="80">](https://play.google.com/store/apps/details?id=app.myzel394.alibi)
[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Get it on F-Droid" height="80">](https://f-droid.org/packages/app.myzel394.alibi)

## 许可证 / License

本项目基于 [GPL v3](LICENSE) 开源。Alibi 原作者为 [Myzel394](https://github.com/Myzel394)。

