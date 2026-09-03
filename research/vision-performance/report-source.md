# Void Music 色块与手部识别性能审查

日期：2026-09-03  
代码基线：`master` @ `b148d4e`

## 结论

现有技术选型适合产品：受控桌面中的彩色物件使用 HSV/OpenCV，手部关键点使用
MediaPipe Hand Landmarker 的 `LIVE_STREAM` 加 GPU 优先/CPU 回退。此时不应更换为
通用物体检测或另一个手部模型；它们会提高算力和交付风险，不能直接解决当前的
端到端延迟。

存在明确的优化空间，优先级应是：修正中档帧率调度、消除色块路径的复制和
颜色空间中转、补足阶段级延迟追踪，然后再考虑并行化。不要先做模型替换。

## 已有优势

- CameraX 使用 `STRATEGY_KEEP_ONLY_LATEST`，分析落后时不会积压旧帧；官方也建议
  对无法跟上帧率的耗时分析走非阻塞、丢旧帧策略。
- Hand Landmarker 采用异步 `LIVE_STREAM`、时间戳化结果和有界队列；MediaPipe 在
  视频/流模式会利用跟踪，避免每帧重新触发掌心检测。
- 色块分割器已复用 morphology kernel，并在关闭时释放 OpenCV 原生资源；命中使用
  未平滑的手部结果，显示层才使用 One Euro 平滑，避免平滑直接吞掉击打峰值。
- 已将分析分辨率限制为 640×480 / 480×360，避免把 1080p 直接送入模型。

## 实测基线与限制

PJZ110 真机的真实彩色物品、手部和击打场景记录为：分析/手部结果各 15 FPS；
颜色分割 P50/P95 为 27/39 ms；鼓区缓存年龄 P50/P95 为 67/134 ms；击打到
`DrumEngine.trigger()` 返回为 175/208 ms。

最后一项是“相机源帧到音频提交”，不包含下一次 Oboe callback、设备 buffer 和
扬声器声学传播，因此不能称为实际出声延迟。当前采集也没有分开记录“相机到手部
回调”和“回调在队列中的等待”，无法把 175–208 ms 精确归因给模型、帧调度或音频。

## 主要发现

### P0：20 FPS 上限在 30 FPS 相机源上会变成约 15 FPS

`FrameRouter.shouldAnalyzeFrame()` 以“上一次已提交帧”为基准，要求间隔至少
`1 / frameCap`。当相机约为 30 FPS、配置为 20 FPS 时，33 ms 的下一帧不够 50 ms，
只能等到约 66 ms 的再下一帧；结果是每两帧处理一次，即约 15 FPS。这会放大手部
回调间隔和命中延迟。现有 15 FPS 真机观测与这一量化行为一致，但下次复测仍应在
设置页确认实际档位。

**建议**：改为“下一个截止时间”或相位累加器，而不是从最后提交帧重新计时。输入
为 30 FPS 时，它可以按 2、1、2、1…帧间隔取样，长期保持约 20 FPS。加入 30→20、
60→20、相机抖动和长暂停恢复的纯逻辑测试。

### P0：HSV 路径有不必要的 Java 堆复制与两次颜色空间转换

CameraX 已输出 RGBA；但当前路径仍依次执行 `ImageProxy.toBitmap()`、可能的
`Bitmap.createBitmap()` 旋转、`Bitmap.getPixels()` 到 `IntArray`、逐像素写入
`ByteArray`、RGBA→BGR、BGR→HSV。每次色块分割又为 4 个预设创建 mask、轮廓和
hierarchy。当前 27/39 ms 的分割时间已超过项目 30 FPS 路径的 25 ms 预算中位数。

**建议**：先做 A/B 微基准，再将 `bitmapToBgrMat()` 替换为 OpenCV 的
`Utils.bitmapToMat()`，直接执行 RGBA→HSV，去掉 `IntArray`、`ByteArray`、BGR Mat
和一次 `cvtColor`。随后复用尺寸稳定的 RGBA/HSV/mask Mat；保留 `close()` 统一释放。
正确性验收必须覆盖红色 Hue 跨 0/180、取色后预设、旋转和异常资源回收。

### P1：色块范围应利用已有校准 ROI，同时改善性能与误识别

默认四个 HSV 预设会把画面中任何同色物都作为候选。真机中黄色包装被正确地按颜色
识别，却不一定是演奏目标。若使用已存在的四点校准区域，将分割限制到该四边形的
外接矩形并过滤多边形外 contour，可同时减少像素量和背景误框。未校准时保持全画面
行为，避免破坏首次使用。

### P1：当前只知道结果速率，不知道手部回调与排队延迟

MediaPipe 的 `LIVE_STREAM` 在忙时会直接忽略新输入；当前代码仍按相机节流调用
`detectAsync()`，但不会记录调用是否被任务忙碌丢弃。结果回调进入容量 4 的队列，
随后要等下一次分析帧才被击打逻辑消费。

**建议**：记录并展示以下分段指标：

1. 相机源时间戳 → `HandTracker.onResult()`（推理 + 框架排队）；
2. 回调 → `drainRawHandFrames()`（队列等待）；
3. 候选 → 音频命令入队；
4. Oboe `onAudioReady()` 出队，以及 `framesPerBurst / sampleRate` 的理论 buffer 下界。

在此基础上增加单个 in-flight 手部提交门：回调或错误返回前不重复构建 `MPImage`。
这符合 MediaPipe 忙碌时忽略新输入的语义；是否提升取决于各档真实的回调 FPS，需
用上述指标验证。

### P2：把同步 HSV 与手部命中泵解耦

`FrameRouter` 在单个分析线程内串行执行手部提交和同步 OpenCV；即使手部提交很快，
27–39 ms 的分割仍延后 `ImageProxy.close()` 和下一次 `drainRawHandFrames()`。可将
HSV 放到一个“仅保留最新任务”的独立执行器，忙时丢弃新的色块任务，继续使用最近
有效的 zones。更进一步可在 MediaPipe 回调侧用原子化的最新 zones 执行命中，去掉
等待下一分析帧的 0–1 帧延迟。

这是并发边界调整，需先完成分段指标与生命周期设计：Bitmap 不能在 MediaPipe 和
OpenCV 仍使用时回收；HitDetector、zone cache、坐标映射和 UI 事件都要各自有单一
线程所有权或明确的原子契约。因此它排在复制优化之后。

### P2：低成本细节

- `OneEuroHandStabilizer` 每个结果会构造 landmarks 列表；它不在原始击打路径上，
  在 15 FPS/两手规模下通常不是首要瓶颈。只在 trace 证明 Compose/GC 成为热点时再
  做可变缓冲或降低 overlay 发布率。
- `Preview.Builder` 与 `ImageAnalysis.Builder` 目前共用相同的 target resolution；
  这与“预览独立保持高质量”的注释不一致。应显式分别协商预览与分析尺寸，但这主要
  是画质/相机流配置问题，不能在没有真机组合测试时贸然提高预览分辨率。
- 固定“每三分析帧”分割会随实际分析 FPS 改变其 Hz。以后可改为独立的时间型色块
  上限，以稳定缓存年龄；前提是先修正 20 FPS 调度并测量命中准确率。

## 不建议现在做的事

- 不替换为通用语义目标检测：产品输入是颜色鲜明的实体，HSV 是更便宜且可本地离线
  调参的方案；背景误识别应先用 ROI/面积/稳定跟踪解决。
- 不更换 Hand Landmarker 模型：现有 LIVE_STREAM 已使用其帧间跟踪优势；目前没有
  证据表明模型本身是 175–208 ms 的主因。
- 不为了“零拷贝”直接改为 CameraX `PRIVATE` 输出：它不可 CPU 访问、不能 `toBitmap()`，
  与当前 OpenCV CPU 分割及 Bitmap 输入的 MediaPipe 方案不兼容。

## 推荐实施顺序与验收

1. **帧调度修复 + 分段 trace（P0）**：中档在 30 FPS 源上达到 19–21 FPS；报告
   capture→callback、callback→hit、hit→audio-submit 的 P50/P95。
2. **RGBA→HSV 简化 + Mat 复用（P0）**：在同一场景、同一档位下，将分割 P95 从当前
   39 ms 降至不高于 25 ms；HSV 检测回归截图与单测不变。
3. **校准 ROI（P1）**：背景黄色包装不再成为鼓区；四个演奏物保持识别；记录分割
   耗时下降比例。
4. **最新帧色块执行器/回调命中（P2）**：仅在第 1 步证明排队是主要来源时实施；
   目标是击打→音频提交 P95 低于 120 ms。
5. **三档与 20 分钟验证**：高/中/低各记录 FPS、上述 P50/P95、PSS/native heap、
   温升和后台恢复；采用 release/profileable 包和真实设备，不能用模拟器代替。

## 证据与来源

- 本地代码：`FrameRouter.kt`、`CameraModule.kt`、`ColorSegmenter.kt`、
  `HandTracker.kt`、`PerformanceConfig.kt`、`VisionMetrics.kt`、`MainScreen.kt`。
- [MediaPipe Hand Landmarker Android 指南](https://developers.google.com/edge/mediapipe/solutions/vision/hand_landmarker/android)：LIVE_STREAM 异步回调、忙时忽略新帧、视频/流模式帧间跟踪与 Bitmap 输入示例。
- [Android CameraX ImageAnalysis 指南](https://developer.android.com/media/camera/camerax/analyze)：KEEP_ONLY_LATEST、RGBA 输出的内部 YUV 转换和分析完成后关闭 ImageProxy 的契约。
- [CameraX ImageAnalysis API](https://developer.android.com/reference/androidx/camera/core/ImageAnalysis)：RGBA 输出格式与最新帧背压的精确定义；亦说明 RGBA/NV21 请求存在转换开销。
- [Android Macrobenchmark 指南](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview) 与 [自定义 TraceSection 指标](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics)：用真实设备、重复测量和 trace section 追踪回归。
- [OpenCV Android Utils API](https://docs.opencv.org/java/2.4.7.1/org/opencv/android/Utils.html)：`bitmapToMat()` 将 ARGB_8888 Bitmap 转为 CV_8UC4 Mat 的 API 契约。该链接用于 API 语义；实际收益必须用当前 OpenCV 4.13.0 真机微基准确认。
