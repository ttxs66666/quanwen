# 圈问 Quanwen

[中文](#中文) · [English](#english)

---

## 中文

**圈出问题，点一下，只给你此刻需要的那一点提示。**

圈问是一个安卓平板上的手写学习助手。你在任何笔记 App 里手写学习，卡住时点一下悬浮条上的「圈」，用笔把问题圈起来，AI 会看着整页笔记，按分寸回答：

| 你圈的是什么 | AI 怎么回 |
|---|---|
| 一个问题（事实、定义） | 直接答，最多两三句 |
| 自己的解释或猜测 | 先说对不对，再给一句理由 |
| 写到一半的推导 | 只给下一步的方向，不给结果 |
| 点「再提示一点」 | 在上一次的基础上多推一步 |
| 点「揭晓」 | 先一句话讲背后的思想，再给简明推导 |

它想做的是：让你自己提问、自己推，AI 只在你卡住的地方推一把。

### 功能

- **悬浮条**有两个按钮：「圈」用来截图圈题；「卡」用来显示或隐藏回答卡片，关掉的卡片随时能再打开。
- 截图时悬浮条和卡片都会先隐藏，不会被截进去。
- 回答卡片可以拖动，能显示 LaTeX 公式，能翻看这次学习里的其他回答。
- 同一次学习里前面问过的内容会一并交给 AI，它会接着往下讲。
- **App 内圈题**：不用悬浮条，开不了无障碍的系统（比如鸿蒙的卓易通）也能用。
  - 分屏，一半笔记、一半圈问。用系统截屏后，圈问会自动载入这张最新截图，在圈问里直接圈题。
  - 也可以从相册选图，或者拍一张纸质笔记来圈。
  - 双指可以缩放。长按桌面图标有「圈最新截图」「拍照圈题」两个快捷方式。
- 有历史记录，界面中英双语，应用内可以检查更新。

### 安装

1. 到 [Releases](https://github.com/ttxs66666/quanwen/releases) 下载最新的 `quanwen-v*.apk`，在平板上安装。需要 Android 11 及以上。
2. 打开「圈问」，点「打开无障碍设置」，把「圈问悬浮条」打开。如果开关是灰的，先点「应用信息」，在右上角菜单里选「允许受限设置」。实在开不了，就用「App 内圈题」。
3. 在设置页填入你自己的 OpenAI API Key，点「测试连接」。安装包里不带任何密钥。
4. 以后在设置页点「检查更新」就能升级，本机保存的密钥和记录都不会丢。

### 隐私

- 只有在你点「圈」的时候才会截屏。
- 截图只发给你配置的 OpenAI 接口。
- 历史记录只存在本机。

### 开发

- 用 Kotlin 写，没有用 AndroidX。
- 截屏用无障碍服务的 `takeScreenshot`。
- 回答卡片用 WebView 加 KaTeX 渲染。
- 设计文档见 [设计.md](设计.md)。

```bash
./gradlew assembleRelease
```

### 许可证

- 版权归 ttxs66666 所有。
- 本项目以 [PolyForm Noncommercial 1.0.0](LICENSE) 许可证发布：可以免费用于学习、个人使用、研究和其他非商业用途，也可以修改；商业用途需要另行取得授权。
- 内置的 [KaTeX](https://katex.org) 使用 MIT 许可证，见 `app/src/main/assets/katex/LICENSE`。

---

## English

**Circle a question, tap once, and get just the hint you need right now.**

Quanwen is a handwriting study companion for Android tablets. Write in any note-taking app. When you get stuck, tap the circle button on the floating bar and circle the question with your pen. The AI reads the whole page and answers with restraint:

| What you circled | How the AI answers |
|---|---|
| A question (fact, definition) | A direct answer, two or three sentences at most |
| Your own explanation or guess | Right or wrong first, then one sentence of reason |
| A derivation that stops halfway | Only the direction of the next step, not its result |
| Tap "A bit more" | One step further than the last hint |
| Tap "Reveal" | The idea behind the step in one sentence, then a concise derivation |

The point: you ask the questions and do the reasoning; the AI only nudges you where you are stuck.

### Features

- **Floating bar** with two buttons: "circle" captures the screen so you can circle a question; "card" shows or hides the answer card, so a closed card can be reopened any time.
- The bar and card hide themselves before the screenshot, so they never appear in it.
- The answer card is draggable, renders LaTeX, and lets you page through the other answers from the session.
- Earlier questions from the same session are passed to the AI, so it can build on them.
- **Circle in the app**: no float bar needed, so it also works where accessibility can't be turned on (for example HarmonyOS with Zhuoyitong).
  - Split the screen with notes on one half and Quanwen on the other. Take a system screenshot and Quanwen loads it automatically, then circle right there.
  - You can also pick an image from the gallery or photograph paper notes.
  - Pinch to zoom. Long-press the launcher icon for the "Circle latest screenshot" and "Circle a photo" shortcuts.
- History, a Chinese/English interface, and in-app updates.

### Install

1. Download the latest `quanwen-v*.apk` from [Releases](https://github.com/ttxs66666/quanwen/releases) and install it on your tablet. Android 11 or later is required.
2. Open Quanwen, tap "Accessibility settings" and turn on "Quanwen float bar". If the switch is greyed out, open App info and choose "Allow restricted settings" from the top-right menu. If it still can't be turned on, use "Circle in the app".
3. Enter your own OpenAI API key in Settings and tap "Test connection". The APK ships without any key.
4. Later, tap "Check for updates" in Settings to upgrade. Your saved key and history stay on the device.

### Privacy

- The screen is captured only when you tap the circle button.
- Screenshots go only to the OpenAI endpoint you configured.
- History stays on the device.

### Development

- Written in Kotlin, without AndroidX.
- Screen capture uses the accessibility service's `takeScreenshot`.
- The answer card is rendered with a WebView and KaTeX.
- The design notes (in Chinese) are in [设计.md](设计.md).

```bash
./gradlew assembleRelease
```

### License

- Copyright (c) 2026 ttxs66666.
- Released under the [PolyForm Noncommercial License 1.0.0](LICENSE): free for study, personal use, research and other noncommercial purposes, including modification; commercial use requires a separate license.
- The bundled [KaTeX](https://katex.org) is MIT-licensed; see `app/src/main/assets/katex/LICENSE`.
