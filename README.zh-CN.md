<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="./icon.png">
  <source media="(prefers-color-scheme: light)" srcset="./icon.png">
  <img src="./icon.png" alt="OpenDroid" width="160">
</picture>

# OpenDroid

[English](./README.md)

</div>

## 简介

OpenDroid 在 **安卓本机** 运行 **智能体**，通过 **HTTP API** 调用 **云端大模型**。对话会触发工具链，**读取当前界面**并执行点击、滑动等操作，需要时还可向支持视觉的模型提供 **轻量截图**。**Skill** 用于沉淀可复用的流程。

---

## 安装

我们提供可直接安装的 **APK**。请在本仓库的 **Releases** 页面下载最新安装包，在手机上安装（需要 **Android 8.0 及以上**，即 API **26+**）。按系统与安装来源提示允许安装即可。

---

## Quickstart（快速上手）

设置与上手步骤也可参考[视频教程](https://www.youtube.com/shorts/MHWSLME-k7Q)。

1. 打开系统 **设置 → 无障碍**，找到 **OpenDroid**，**开启无障碍服务**。在 **Android 12 及以上** 若系统询问，请一并允许服务的 **截图** 相关权限，否则无法为视觉模型提供全屏截图类能力。
2. 为 OpenDroid 开启 **悬浮窗**（**显示在其他应用的上层**）权限，以便任务执行期间浮层与前台体验正常。入口因机型而异，通常在 **设置 → 应用 → OpenDroid** 的相关权限页；也可在 **最近任务** 界面长按应用卡片或图标，进入 **应用信息 / 单个应用设置** 后再打开。
3. 进入 OpenDroid，填写 **API** 相关信息（若安装包已自带默认配置可直接沿用），并在应用内按需 **打开悬浮窗**。
4. 进入 **对话**，用自然语言描述目标，**运行过程中尽量不要操作手机**，以免打断自动化。

---

## Demo 视频

以下为 **Mobile AI Agent** 演示视频。

1. **Mobile AI Agent 为 Github 仓库点星**

   [![Mobile AI Agent 为开源仓库点星](./static/demos/Screenrecorder-2026-04-04-04-37-11-407_first_frame.jpg)](https://www.youtube.com/shorts/WxQkucIl0Uk)

2. **Mobile AI Agent 在应用内查看最新照片**

   [![Mobile AI Agent 在应用内查看最新照片](./static/demos/Screenrecorder-2026-04-04-04-42-14-668_first_frame.jpg)](https://www.youtube.com/shorts/wxRjIb7N81E)

<br>

3. **Mobile AI Agent 在视频应用里播放短片**

   [![Mobile AI Agent 在视频应用里播放短片](./static/demos/Screenrecorder-2026-04-04-04-10-36-860_first_frame.jpg)](https://www.youtube.com/shorts/HEgmJSzulEc)

<br>

4. **Mobile AI Agent 关注社交账号**

   [![Mobile AI Agent 关注社交账号](./static/demos/Screenrecorder-2026-04-04-04-12-49-501_first_frame.jpg)](https://www.youtube.com/shorts/MyszLbsT7CA)

<br>


---

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.
