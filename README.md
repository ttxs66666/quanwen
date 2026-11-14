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
| 点「追问」，再选书写板 / 语音 / 打字 | 直接回答你问的那一点，仍然不顺带说出后面的步骤 |

它想做的是：让你自己提问、自己推，AI 只在你卡住的地方推一把。

### 功能

- **悬浮条**有两个按钮：「圈」用来截图圈题；「卡」用来显示或隐藏回答卡片，关掉的卡片随时能再打开。
- 截图时悬浮条和卡片都会先隐藏，不会被截进去。
- **追问**：回答卡片中间的「追问」，点开有三种方式。
  - ✍ 书写板：用笔把问题写下来，AI 直接看字迹，回答前先复述它认出的内容。
  - 🎤 语音：说完点一下，用 OpenAI 转成文字；几乎没声音时不会发出去。
  - ⌨ 打字：在输入框里打字。
- 回答卡片可以拖动，能显示 LaTeX 公式，能翻看这次学习里的其他回答。
- **会话**：回答面板右上角的 ☰ 可以新建会话、切换会话。新圈的题只进当前会话，AI 也只参考当前会话里前面的问答，复习一门、再学另一门，互不干扰。
- **App 内圈题**：不用悬浮条，开不了无障碍的系统（比如鸿蒙的卓易通）也能用。
- **自带笔记本**（第 12 版起）：书架上新建笔记本、选封面；米黄色横向纸张，黑红蓝三色、三档粗细，一笔一撤，每 3 秒自动保存。用过触控笔以后只有笔能写，手指用来滚动。
  - 第 13 版起可以在笔记本里直接圈题：点工具栏的「圈」，用荧光笔圈出问题，停笔 1 秒就发给 AI；荧光圈会自己淡掉。回答框出现在圈的附近、比较空的地方，避开右手放的位置，可以拖标题栏挪开。追问就是在纸上接着写、再圈。点「留下」把回答变成一张图贴在纸上；工具栏里打开「移动图片」才能拖动或删除这些图，免得写字时误碰。会话在工具栏右边的下拉菜单里切换。
  - 分屏，一半笔记、一半圈问。点圈题页的「截屏」，圈问会截下笔记那一半（自动去掉圈问自己那半屏），截图只临时用，不存相册。也可以用系统截屏，圈问会自动载入最新截图。
  - 也可以把截图（或别的 App 里的图片）直接拖进圈问；还可以从相册选图、粘贴，或者拍一张纸质笔记来圈。
  - 双指可以缩放。长按桌面图标有「圈最新截图」「拍照圈题」两个快捷方式。
- 有历史记录，界面中英双语，应用内可以检查更新。

### 安装

1. 到 [Releases](https://github.com/ttxs66666/quanwen/releases) 下载最新的 `quanwen-v*.apk`，在平板上安装。需要 Android 11 及以上。
2. 打开「圈问」，开启悬浮条，两种方式任选一种：
   - **悬浮窗（推荐）**：点「开启悬浮窗」，允许「显示在其他应用上层」。第一次点「圈」时系统会问一次录屏授权，之后直接截屏；手机重启或录屏被停止后再问一次。不用开无障碍。
   - **无障碍**：点「打开无障碍设置」，把「圈问悬浮条」打开。如果开关是灰的，先点「应用信息」，在右上角菜单里选「允许受限设置」。有些手机清理后台时会把无障碍关掉，这种手机用悬浮窗方式更省事。
   - 两种都开不了（比如鸿蒙的卓易通），就用「App 内圈题」。
3. 在设置页填入你自己的 OpenAI API Key，点「测试连接」。安装包里不带任何密钥。
4. 以后在设置页点「检查更新」就能升级，本机保存的密钥和记录都不会丢。

### 隐私

- 只有在你点「圈」的时候才会截屏。
- 截图只发给你配置的 OpenAI 接口。
- 历史记录只存在本机。

### 开发

- 用 Kotlin 写，没有用 AndroidX。
- 悬浮窗方式：`TYPE_APPLICATION_OVERLAY` 窗口加前台服务，截屏用录屏接口（MediaProjection），只在点「圈」时取一帧。
- 无障碍方式：截屏用无障碍服务的 `takeScreenshot`。
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
| Tap "Ask", then write, speak or type | A direct answer to exactly what you asked, still without volunteering later steps |

The point: you ask the questions and do the reasoning; the AI only nudges you where you are stuck.

### Features

- **Floating bar** with two buttons: "circle" captures the screen so you can circle a question; "card" shows or hides the answer card, so a closed card can be reopened any time.
- The bar and card hide themselves before the screenshot, so they never appear in it.
- **Ask a follow-up**: tap "Ask" in the middle of the answer card and pick one of three ways.
  - ✍ Write: write the question on a pad; the AI reads your handwriting and first repeats what it read.
  - 🎤 Voice: talk, then tap when done; transcribed by OpenAI, and near-silent recordings are not sent.
  - ⌨ Type: type it in a text box.
- The answer card is draggable, renders LaTeX, and lets you page through the other answers from the session.
- **Sessions**: the ☰ button at the top right of the answer panel creates or switches sessions. New questions go into the current session, and the AI only builds on earlier questions from that session, so reviewing one subject and then studying another don't mix.
- **Circle in the app**: no float bar needed, so it also works where accessibility can't be turned on (for example HarmonyOS with Zhuoyitong).
- **Built-in notebooks** (from version 12): create notebooks on a bookshelf and pick a cover. Cream landscape paper, black/red/blue pens in three widths, one-stroke undo, auto-save every 3 seconds. Once you've used a stylus, only the stylus writes and fingers scroll.
  - From version 13 you can circle questions right in the notebook: tap the circle tool in the toolbar, loop the question with the highlighter, and it goes to the AI when you pause for 1 second; the highlight fades by itself. The answer box appears next to the circle in an emptier spot, away from where your right hand rests, and you can drag it by its title bar. To follow up, keep writing on the page and circle again. Tap Keep to pin the answer to the page as a picture; turn on Move pictures in the toolbar to drag or delete pictures, so you don't move them by accident while writing. Switch sessions from the dropdown at the right of the toolbar.
  - Split the screen with notes on one half and Quanwen on the other. Tap Screenshot on the circle page and Quanwen captures the notes half (cropping out its own half); the capture is temporary and never saved to the gallery. A system screenshot also works: Quanwen loads the latest one automatically.
  - You can also drag a screenshot (or an image from another app) straight into Quanwen, pick one from the gallery, paste one, or photograph paper notes.
  - Pinch to zoom. Long-press the launcher icon for the "Circle latest screenshot" and "Circle a photo" shortcuts.
- History, a Chinese/English interface, and in-app updates.

### Install

1. Download the latest `quanwen-v*.apk` from [Releases](https://github.com/ttxs66666/quanwen/releases) and install it on your tablet. Android 11 or later is required.
2. Open Quanwen and turn on the float bar in either of two ways:
   - **Overlay (recommended)**: tap "Turn on overlay" and allow "Display over other apps". The first time you tap the circle button the system asks once for screen-capture permission; after that screenshots work directly, and it asks again after a restart or if capture was stopped. No accessibility needed.
   - **Accessibility**: tap "Accessibility settings" and turn on "Quanwen float bar". If the switch is greyed out, open App info and choose "Allow restricted settings" from the top-right menu. Some phones turn accessibility off when they clean up background apps; on those, overlay mode is easier.
   - If neither works (for example HarmonyOS with Zhuoyitong), use "Circle in the app".
3. Enter your own OpenAI API key in Settings and tap "Test connection". The APK ships without any key.
4. Later, tap "Check for updates" in Settings to upgrade. Your saved key and history stay on the device.

### Privacy

- The screen is captured only when you tap the circle button.
- Screenshots go only to the OpenAI endpoint you configured.
- History stays on the device.

### Development

- Written in Kotlin, without AndroidX.
- Overlay mode: `TYPE_APPLICATION_OVERLAY` windows plus a foreground service; screenshots come from the screen-capture API (MediaProjection), one frame per tap of the circle button.
- Accessibility mode: screenshots come from the accessibility service's `takeScreenshot`.
- The answer card is rendered with a WebView and KaTeX.
- The design notes (in Chinese) are in [设计.md](设计.md).

```bash
./gradlew assembleRelease
```

### License

- Copyright (c) 2026 ttxs66666.
- Released under the [PolyForm Noncommercial License 1.0.0](LICENSE): free for study, personal use, research and other noncommercial purposes, including modification; commercial use requires a separate license.
- The bundled [KaTeX](https://katex.org) is MIT-licensed; see `app/src/main/assets/katex/LICENSE`.
