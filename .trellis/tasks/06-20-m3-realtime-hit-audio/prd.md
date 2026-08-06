# M3: 实时击打模式 + Oboe 音频引擎

## Goal

实现完整实时击打链路：HandStabilizer → HitDetector → HitArbiter → PadTracker → DrumEngine (Oboe)，端到端 "手指触碰物件 → 鼓声播放"，含力度感知和振动反馈。

覆盖 PRD F2、F5.3-F5.6、F6。工期 2 周。

## Requirements

### R3.1 HandStabilizer 稳定器（PRD F5.5）

- 对 MediaPipe 原始指尖坐标做指数滑动平均（EMA）或 One Euro Filter。
- 平滑系数 `stabilizerSmoothing` 可配置。
- 输入：`List<Point>`（指尖原始坐标），输出：稳定后的坐标流。

### R3.2 HitDetector 击打检测（PRD F5.3）

- 监测指尖 Y 轴方向（垂直/深度）的速度变化。
- 速度越过阈值 + 方向向下 → 生成 `HitCandidate`。
- 输出：`List<HitCandidate>`（含置信度、速度估计）。

### R3.3 HitArbiter 击打仲裁（PRD F5.4）

- 同一帧多个候选 / 多指同时触发时裁决唯一命中。
- 依据：置信度、距最近 DrumZone 中心距离、防抖时间窗。
- 避免一次物理敲击触发多次。
- 输出：`List<TriggerEvent>`（可能为空）。

### R3.4 PadTracker 落点检测（PRD F5.6）

- 判定击打落点位于哪个 `DrumZone`。
- 基于指尖位置与 DrumZone 轮廓/中心的空间关系判定。
- 支持落在空白区域时不触发（静默）。

### R3.5 DrumEngine 音频引擎（PRD F6.1-F6.2）

- 基于 Oboe (AAudio) 的低延迟播放。
- 内置鼓组：Kick、Snare、Clap、Tom、Hi-Hat 等（至少 5 种）。
- 样本加载为 `raw` 资源，或合成器生成。
- 支持力度控制 volume（0..1）。
- 支持多声道同时发声（混音）。

### R3.6 力度与触觉（PRD F2.4-F2.5）

- Velocity 估算：基于指尖下落速度/加速度映射到 0..1。
- 击打命中时触发 HapticFeedback（轻微振动 ~ 10-20ms）。

### R3.7 模式切换（PRD F2.1）

- 主界面在「实时击打」与「步进序列器」（M4）间切换。
- 切换时保持校准和物件识别状态（不重新初始化）。

## Acceptance Criteria

- [ ] 手指触碰桌面色块 / 贴纸 → 对应鼓声稳定触发（误触率 < 10% 初步）
- [ ] 击打→出声端到端延迟 < 120ms（初步验收，M6 内达标 < 100ms）
- [ ] 力度不同时音量/音色有可感知变化
- [ ] 振动反馈在击打命中时可感知
- [ ] 多指同时击打不同 DrumZone 分别触发正确鼓声
- [ ] 无击打时静默，不误触（手指悬空不触发）
- [ ] Oboe 音频播放无明显杂音、卡顿
- [ ] 主界面模式切换流畅（击打 ↔ 步进）

## Out of Scope

- Transport 节拍时钟（M4）
- 步进序列器 UI（M4）
- 自定义样本导入（M5 P2）
- 音色组切换（M5）
- 多手同时操作精细处理（P1，可在 M3 初步支持）

## Dependencies

- 依赖 M0：CameraX + 基础架构
- 依赖 M1：HandLandmarker 接入 + 指尖坐标流
- 依赖 M2：DrumZone 识别 + HSV 物件
- 推荐先完成 M1、M2 后再做 M3 端到端联调
