# 视觉性能优化

## Goal

降低 Void Music 彩色物件与手部击打链路的端到端等待时间，优先修正中档帧率失真、色块分割热路径复制和可观测性缺口；保持本地离线、现有 HSV 与 MediaPipe 技术路线及既有交互语义。

## Confirmed Facts

- 当前 `FrameRouter` 按距最后提交帧的间隔限流；30 FPS 相机源配 20 FPS 上限时会每两帧提交一次，长期约为 15 FPS。
- HSV 路径目前经历 Bitmap、`IntArray`、`ByteArray`、RGBA→BGR→HSV；每次分割对 4 个 HSV 预设执行阈值、形态学和轮廓查找。
- PJZ110 实测：手部结果 15 FPS；分割耗时 P50/P95 为 27/39 ms；鼓区缓存年龄 P50/P95 为 67/134 ms；击打到音频提交 P50/P95 为 175/208 ms。
- 当前指标没有拆分相机到 MediaPipe 回调、回调队列等待和音频命令提交三个阶段，无法直接归因。
- `LIVE_STREAM`、有界手部结果队列、GPU 优先和 CameraX `KEEP_ONLY_LATEST` 已建立，均应保留。

## Requirements

- HIGH/MEDIUM/LOW 的 `analysisFrameCap` 必须是长期目标提交速率，而不是被相机离散帧率量化后的更低速率。
- 记录并展示：相机源帧→手部回调、手部回调→击打消费、候选→音频提交的 P50/P95；原有分析 FPS、手部结果 FPS、分割耗时、缓存年龄保持可用。
- 色块分割须从 RGBA 直接转 HSV，移除手写像素数组中转；尺寸稳定时复用 OpenCV 工作 Mat，并可安全关闭释放。
- 不改变颜色预设、分区坐标、手部模型、音频映射、API 26+ 或离线处理。

## Acceptance Criteria

- [ ] 对模拟 30 FPS 输入的 20 FPS 上限，1 秒内提交 19–21 帧；同时覆盖 60→20、首次帧、暂停恢复和无上限边界。
- [ ] HUD 可显示 capture→callback、callback→consume、击打→提交的 P50/P95；无样本时不显示伪造数值。
- [ ] 色块路径不再创建 `IntArray(w*h)` 与 `ByteArray(w*h*4)`，并从 RGBA 一步转 HSV；关闭后所有缓存 Mat 均释放且不可再使用。
- [ ] HSV 红色跨 Hue 边界、四个默认颜色、旋转坐标和取色行为不回归。
- [ ] 同一 PJZ110、同一场景和性能档位下，分割 P95 不高于 25 ms 或相较 39 ms 基线下降至少 25%；击打提交延迟有分段数据可归因。
- [ ] JVM 测试、Lint、debug APK 构建通过；真机完成当前档位彩色物体和手部击打复测。

## Out of Scope

- 校准 ROI、色块独立执行器、MediaPipe 回调直接命中、通用目标检测、更换手部模型。
- Oboe 音频 callback 的 native trace 与扬声器声学延迟测量。
- 三档 20 分钟长稳、温升和后台恢复复测；它们留给既有发布性能任务。

## Reference

- [性能审查报告](../../../research/vision-performance/report-source.md)
