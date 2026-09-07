# Void Music 技术原理

本文档说明 Void Music `v0.1.0-m11` 的核心实时链路与关键设计取舍。仓库首页面向使用者，本页面向维护者；参数和行为以当前代码为准。

## 1. 整体数据流

```text
CameraX ImageAnalysis
  → FrameRouter
    → 按性能档位限制分析帧率
    → ImageProxy 转 Bitmap
    → 按 rotationDegrees 旋转到屏幕方向
    → 同一帧分发给两个消费者
      ├─ HandTracker.detectAsync()       异步 MediaPipe 推理
      │   ├─ 原始手部帧 → 实时击打 / 步进格触发
      │   └─ One-Euro 平滑 → Compose 手部骨架
      └─ LivePerformancePipeline
          ├─ 自适应 OpenCV HSV 分割 → 稳定鼓区缓存
          ├─ 取景器取色
          └─ 步进模式中的网格命中检测

MediaPipe 结果回调
  → 新鲜度检查
  → HitDetector
  → PadTracker / HitArbiter
  → DrumEngine.trigger()
  → Oboe，失败时回退 SoundPool
```

`LivePerformancePipeline` 将 CameraX、MediaPipe、OpenCV 缓存、击打检测和观测指标收拢在一个生命周期边界内。UI 只提供不可变配置并消费事件，避免 Compose 重组直接参与实时线程的数据协调。

## 2. 物品识别

### 2.1 HSV 分割管线

```text
Bitmap
  → RGBA Mat
  → 按性能档位降采样
  → RGB(A) 转 HSV
  → 每个颜色预设执行 inRange
  → 形态学开运算 + 闭运算
  → 外轮廓检测
  → 面积和长宽比过滤
  → DrumZone
  → ZoneTracker 分配稳定 ID
```

OpenCV 的色相范围为 `H ∈ [0,180]`，饱和度和亮度为 `S/V ∈ [0,255]`。默认映射如下：

| 颜色 | H 范围 | S 范围 | V 范围 | 鼓垫 |
|---|---:|---:|---:|---|
| 红 | 160–8（跨边界） | 100–255 | 50–255 | Kick |
| 蓝 | 95–130 | 80–255 | 50–255 | Snare |
| 绿 | 45–85 | 80–255 | 50–255 | Clap |
| 黄 | 22–35 | 80–255 | 50–255 | Tom |

默认轮廓面积占画面的 `0.001–0.15`，可接受长宽比为 `0.3–3.5`。较高的饱和度下限和最小面积用于减少皮肤、指甲及背景小色斑造成的误识别。

### 2.2 红色色相环绕

红色横跨 HSV 色相环的起点和终点。当 `hMin > hMax` 时，阈值逻辑不会把范围视为无效，而是分别计算：

- `H = hMin..180`
- `H = 0..hMax`

两张掩码通过 `bitwise_or` 合并。这样可以同时覆盖暗红、品红侧和纯红侧，并保持其他颜色使用单段 `inRange`。

### 2.3 自适应分割频率与鼓区缓存

物品位置变化通常慢于手指运动，因此 OpenCV 不必在每个分析帧执行：

- 新场景或鼓区仍在变化时，高于 18 FPS 的档位每 2 帧分割一次；低帧率档位每帧分割。
- 鼓区连续稳定 4 次后进入低频阶段，执行间隔根据分析帧率动态计算。
- 模式或 HSV 配置变化后，下一帧强制重新分割。
- 分割间隔始终受 260 ms 鼓区新鲜度上限约束，并预留 60 ms 调度余量。

`ZoneTracker` 根据颜色种类、交并比和中心距离为轮廓维持稳定 ID。轨迹最多保留 500 ms，避免物品短暂抖动造成鼓区身份变化，也避免较晚出现的无关物品继承旧鼓区的触发冷却。

### 2.4 从取景器取色

界面点击位置会先通过 `PreviewCoordinateMapper.unmap()` 还原到分析图坐标，再从下一张有效相机帧采样。取色器使用圆形核心区域、色相圆周均值以及 S/V 分位数生成带缓冲的 HSV 范围，从而降低单个高光或暗点对结果的影响。

## 3. 手部识别

### 3.1 MediaPipe 配置

- 模型：`hand_landmarker.task`，约 7.5 MiB
- 模式：`RunningMode.LIVE_STREAM`
- 默认置信度：检测、存在与追踪均为 `0.5`
- 最大手数：由高、中、低性能档位决定
- Delegate：优先使用档位指定的 GPU/CPU；GPU 初始化失败时自动尝试 CPU

模型内部包含手掌检测和 21 个关键点回归。`detectAsync()` 不阻塞相机分析线程，MediaPipe 会丢弃被新输入替代的帧，因此不会形成无限增长的推理队列。

### 3.2 原始与平滑双路径

同一份推理结果被拆成两条用途不同的路径：

- **平滑路径**：One-Euro 滤波后的 `StateFlow<List<Hand>>`，只用于骨架叠加，减少视觉抖动。
- **原始路径**：未经平滑的 `TimestampedHands`，进入容量为 4 的有界队列或直接由回调消费，用于击打判断。

平滑滤波会削弱敲击瞬间的速度峰值，因此不能复用到触发路径。双路径设计让画面保持稳定，同时保留快速下击所需的原始运动信息。

## 4. 实时击打

### 4.1 指尖轨迹与候选生成

`HitDetector` 使用屏幕方向坐标；Y 增大代表向下移动。当前判定依据是相邻有效帧之间的向下速度：

```text
downwardSpeed = (currentY - previousY) × 1000 / dtMs
```

默认击打速度阈值为 `0.5` 个归一化画面高度/秒，用户可在设置中调整。检测器不会依赖 MediaPipe 返回列表的顺序，而是结合左右手信息和最近指尖距离维持轨迹：

- 相邻帧最大匹配距离：`0.32`
- 丢失轨迹保留时间：`500 ms`
- 默认重新武装时间：`60 ms`
- 提前抬手距离：`0.018`

一次下击触发后，手指必须出现抬起动作并满足重新武装时间，才能产生下一次候选。抬手可以早于冷却结束被记住，使紧接其后的下击不会因一帧时序差而丢失。

### 4.2 结果新鲜度

MediaPipe 结果携带相机源时间戳和回调完成时间。`TapHitProcessor` 在触发前检查两类年龄：

- 手部结果：目标预算 `140 ms`，根据设备近期推理耗时做指数估计，并增加 `40 ms` 抖动余量；硬上限为 `260 ms`。
- 鼓区缓存：消费时必须处于 `0–260 ms`。

过旧手势不会在延迟后补发声音。新鲜度校验发生在 MediaPipe 回调路径中，避免先经过 Compose 状态更新再触发音频。

### 4.3 鼓区匹配与重复触发

`PadTracker` 先在扩展后的物品边框中选择中心最近的鼓区；未落入边框时，再接受距离中心 `0.15` 以内的最近鼓区。边框扩展量为宽高的 `0.12`，用于容忍指尖落点和物体轮廓之间的小偏差。

`HitArbiter` 对每个稳定鼓区单独维护 `70 ms` 重复触发冷却，因此快速切换颜色不会被另一个鼓区的冷却阻塞。向下速度通过软饱和函数映射到 `0.25–1.0` 的播放增益：

```text
gain = clamp(speed / (speed + 1.5), 0.25, 1.0)
```

## 5. AR 步进序列

### 5.1 四点透视投影

用户标定四个角后，`GridProjection` 为该凸四边形建立单位正方形与屏幕区域之间的 3×3 单应性变换。无效角点数量、越界坐标、非凸四边形或不可逆矩阵都会被拒绝，不会生成错误网格。

单位正方形被划分为 4 行 × 16 列，正向变换生成每个格子的屏幕中心，逆向变换则把指尖落点还原到行列索引。这样网格可以跟随桌面视角产生透视变形，而不要求摄像头正对矩形区域。

四行依次对应：

1. Kick
2. Snare
3. Clap
4. Hi-Hat

步进模式复用原始手部轨迹和 `HitDetector`，命中格子后切换其开关状态；同一格子的切换冷却为 `350 ms`，用于避免一次按压重复翻转。

### 5.2 节拍时钟

`Transport` 使用单调时钟和绝对截止时间驱动十六分音符。下一拍基于上一个音乐截止时间计算，而不是基于本次任务完成时间计算，因此每拍的执行开销不会累积成速度漂移。

如果线程唤醒时已经错过多个步进，过期步进会被跳过，不会集中补发形成意外的连击。BPM 范围为 `40–220`，停止播放时播放头回到第 0 步，网格和最近 BPM 会持久化，但应用恢复时不会自动开始播放。

## 6. 相机方向与坐标映射

`ImageProxy.toBitmap()` 产生传感器原生方向的图像。`FrameRouter` 在分发前统一应用 `imageInfo.rotationDegrees`，因此手部识别、颜色分割和击打检测都接收屏幕方向 Bitmap，不需要各自推导旋转。

`PreviewView` 使用 `FILL_CENTER` 时可能裁切图像边缘。`PreviewCoordinateMapper` 根据源图和视口尺寸计算统一缩放及水平/垂直裁切量，并提供双向映射：

- 分析坐标 → 预览坐标：绘制物品框、手部骨架和命中反馈。
- 预览坐标 → 分析坐标：取景器取色和四点标定。

显示旋转变化时只更新 CameraX use case 的 `targetRotation`，无需重新绑定相机。

## 7. 生命周期与资源管理

`LivePerformancePipeline.start()` 初始化 OpenCV、MediaPipe、分割器并绑定 CameraX；`stop()` 解除相机绑定，关闭 MediaPipe/OpenCV 原生资源，清空鼓区快照、取色请求和步进冷却；`close()` 则永久释放整条管线。

性能档位改变时，新的管线会使用对应的相机分辨率、分析帧率、MediaPipe delegate、最大手数和颜色降采样比例。运行时仅切换模式或 HSV 参数时，通过原子不可变快照更新，不重建昂贵的识别组件。

## 8. 音频链路

音色在进入实时层前会被完整准备为 `PreparedKit`：五个鼓垫必须都有非空 PCM 数据，并共享有效采样率。实时触发路径不执行文件读取。

`DrumEngine` 的启动顺序为：

1. 尝试加载 `libdrumengine.so` 并启动 Oboe/AAudio。
2. 原生库不存在或启动失败时，清理残留资源并启动 SoundPool。
3. 两个后端都失败时进入明确的失败状态，不伪装为可播放。

切换音色时如果新音色启动失败，引擎会恢复上一套音色。切换窗口内的击打进入有界队列，后端恢复后再按顺序提交；队列满时丢弃最早事件，避免无限积压。原生后端还会周期性检查 stream error，异常时执行停止和恢复流程，并暴露 dropped trigger、xRun 和错误码用于诊断。

## 9. 持久化边界

- DataStore 保存性能档位、主音量、HSV 配置、击打参数、最近模式、BPM、步进网格和四点标定。
- Room 保存音色库、音频资产及鼓垫映射关系。
- 导入的 WAV 文件经过格式、大小和时长校验后规范化到应用私有存储；数据库只保存稳定资产标识，不向 UI 暴露绝对路径。
- 内置音色和导入音色最终都转换为统一的 `PreparedKit`，因此 Oboe 与 SoundPool 不需要感知音色来源。

## 10. 关键源码索引

| 主题 | 主要文件 |
|---|---|
| 实时管线 | [`LivePerformancePipeline.kt`](../app/src/main/java/com/electrodig/voidmusic/performance/LivePerformancePipeline.kt) |
| 相机帧路由 | [`FrameRouter.kt`](../app/src/main/java/com/electrodig/voidmusic/camera/FrameRouter.kt) |
| 坐标映射 | [`PreviewCoordinateMapper.kt`](../app/src/main/java/com/electrodig/voidmusic/camera/PreviewCoordinateMapper.kt) |
| HSV 分割 | [`ColorSegmenter.kt`](../app/src/main/java/com/electrodig/voidmusic/detection/color/ColorSegmenter.kt) |
| 自适应分割 | [`SegmentationCadence.kt`](../app/src/main/java/com/electrodig/voidmusic/performance/SegmentationCadence.kt) |
| 手部追踪 | [`HandTracker.kt`](../app/src/main/java/com/electrodig/voidmusic/detection/hand/HandTracker.kt) |
| 击打检测 | [`HitDetector.kt`](../app/src/main/java/com/electrodig/voidmusic/detection/hit/HitDetector.kt) |
| 触发仲裁 | [`HitArbiter.kt`](../app/src/main/java/com/electrodig/voidmusic/detection/hit/HitArbiter.kt) |
| 透视网格 | [`GridProjection.kt`](../app/src/main/java/com/electrodig/voidmusic/detection/grid/GridProjection.kt) |
| 节拍时钟 | [`Transport.kt`](../app/src/main/java/com/electrodig/voidmusic/audio/Transport.kt) |
| 音频后端 | [`DrumEngine.kt`](../app/src/main/java/com/electrodig/voidmusic/audio/DrumEngine.kt) |

