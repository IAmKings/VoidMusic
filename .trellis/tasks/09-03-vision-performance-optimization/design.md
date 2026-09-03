# 视觉性能优化 — 设计

## 帧调度

`FrameRouter` 维护下一个允许提交的单调时间 `nextDueNs`。首次帧立即处理；后续帧若未到截止时间则关闭 `ImageProxy`。处理一帧后以固定间隔推进 deadline，而不是将其重置为当前帧时间；若相机暂停导致跨越多个 interval，则推进到未来第一个 deadline。这样 30 FPS 输入下的 20 FPS 目标会以 2、1、2、1…源帧间隔交替。

## 延迟指标契约

```
CameraX source timestamp → MediaPipe callback completedAtMs
  → TimestampedHands consumedAtMs → HitCandidate → DrumEngine.trigger returnedAtMs
```

`TimestampedHands` 保留 callback 完成时间；原始源时间戳仍是击打速度与顺序的唯一时间基准。候选→提交从命中仲裁完成开始，到 `DrumEngine.trigger()` 返回为止；它不冒充扬声器出声时延。`VisionMetricsRecorder` 仅接收非负 duration，在有界滑动窗口中计算 P50/P95，最多每秒向 UI 发布一次。无样本字段保持 -1，不显示为 0。

## 色块分割

`ColorSegmenter` 将 `Bitmap` 通过 OpenCV `Utils.bitmapToMat()` 写入可复用 RGBA Mat，再以 OpenCV 4.13 所提供、支持 4 通道输入的 `COLOR_RGB2HSV` 直接转为 HSV（alpha 被忽略，不再经历 RGBA→BGR 中转）。每个预设复用同尺寸 mask；红色 Hue 环绕所需临时 mask 也由分割器所有。关闭时统一 release 这些 Mat 与 morphology kernel。

分割器的 Kotlin 对象在 `MainScreen` 组合阶段创建，早于加载 OpenCV native 库的 `LaunchedEffect`；因此 RGBA/HSV Mat 必须在首次 `segment()` 时惰性创建，不能作为字段初始化式。

不在本轮改变分割频率或线程模型，确保坐标、缓存年龄与命中契约不同时变化。

## 风险与回退

- deadline 调度必须使用 CameraX 单调纳秒时间，不可混用 wall clock。
- Mat 复用前须在尺寸或预设数量变化时重新创建，避免 OpenCV native 崩溃；异常路径同样释放。
- `Utils.bitmapToMat()` 的通道契约通过 HSV 默认预设回归和真机截图验证。
- 任一视觉回归可独立回退本任务提交，不涉及持久化迁移。
