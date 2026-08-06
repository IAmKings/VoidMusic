# Object Drum Studio Android — 完整移植

## Goal

交付功能与网页版 [Object Drum Studio](https://github.com/Electro-Dig/object-drum-studio-public) 等价的安卓原生 AR 鼓机 App。纯本地运行，无网络依赖。

工期约 11 周，6 个里程碑。

## Requirements

### 功能覆盖（全部对齐网页版）

| 编号 | 功能域 | 对应里程碑 | 优先级 |
|------|--------|-----------|--------|
| F1 | 启动 / 权限 / 校准 | M0, M2 | P0 |
| F2 | 实时击打模式（Touch/Tap） | M3 | P0 |
| F3 | AR 步进序列器（4×16） | M4 | P0 |
| F4 | 物件识别与 HSV 取色 | M2 | P0 |
| F5 | 手部追踪与击打检测 | M1, M3 | P0 |
| F6 | 音频引擎（Oboe 低延迟） | M3 | P0 |
| F7 | 音色样本库与导入 | M5 | P1 |
| F8 | 设置与本地持久化 | M5 | P0 |

### 非功能指标

| 指标 | 目标值 |
|------|--------|
| 视觉帧率 | ≥ 30 FPS（中端）/ ≥ 60 FPS（高端） |
| 手部追踪帧率 | ≥ 25 FPS |
| 击打→出声延迟 | < 100 ms（P0）/ < 80 ms（P1） |
| 音频流延迟 | < 40 ms |
| 最低系统版本 | Android 8.0（API 26） |
| 连续运行稳定性 | 20 分钟无崩溃、无明显发热（≤ 12°C） |
| 纯本地运行 | 无 INTERNET 权限，无云端上传 |

### 技术路线

- UI: Jetpack Compose
- 摄像头: CameraX
- 手部追踪: MediaPipe Tasks Android (HandLandmarker)
- 计算机视觉: OpenCV Android（HSV/连通域/透视）
- 音频: Oboe (AAudio)
- 持久化: DataStore (Preferences) + Room
- DI: Hilt
- 异步: Kotlin Coroutines + Flow

### 子任务里程碑

| 子任务 | 里程碑 | 工期 | 依赖 |
|--------|--------|------|------|
| M0 | 项目脚手架、权限、最小可运行 | 1 周 | — |
| M1 | 摄像头取景 + 手部追踪打通 | 1.5 周 | M0 |
| M2 | HSV 物件识别 + 4 点透视校准 | 1.5 周 | M1 |
| M3 | 实时击打模式 + Oboe 音频引擎 | 2 周 | M1, M2 |
| M4 | AR 步进序列器（4×16） | 2 周 | M2 |
| M5 | 设置 / 持久化 / 样本库 | 1.5 周 | M3, M4 |
| M6 | 性能优化与全机型测试 | 1.5 周 | M5 |

## Acceptance Criteria

- [ ] 全部 F1-F8 功能通过根 PRD §12.1 验收清单
- [ ] 全部非功能指标达到根 PRD 第 4 章标准
- [ ] 兼容性矩阵验证覆盖高/中/低三档机型
- [ ] 隐私合规验收通过（§12.4）
- [ ] 功能覆盖率 100% 对齐网页版（§13.2 对照表）

## Out of Scope

- 云端同步 / 账号系统
- 社区 / 分享功能
- iOS 版本
- WebRTC / 远程协作
