# Void Music

AR 桌面鼓机 — 用手机摄像头识别桌面上的彩色物体和手部动作，实时触发鼓声。纯本地处理，无需网络。

## 功能

- **实时击打模式** — 手指点击彩色物体触发对应鼓声（底鼓/军鼓/拍手/嗵鼓/踩镲）
- **AR 步进序列器** — 在纸上画 4×16 网格，手指点击格子编排节奏循环
- **手部追踪** — MediaPipe HandLandmarker 实时追踪指尖位置
- **颜色分割** — OpenCV HSV 检测彩色物体并映射到鼓垫
- **低延迟音频** — Oboe 原生音频引擎（<40ms）或 SoundPool 降级方案
- **三档性能** — 高/中/低性能档位，适配不同机型

## 系统要求

- Android 8.0 (API 26) 及以上
- 后置摄像头
- 推荐 arm64-v8a 设备（骁龙 6 系及以上）

## 技术栈

| 模块 | 技术 |
|------|------|
| UI | Jetpack Compose + Material 3 |
| 相机 | CameraX (Preview + ImageAnalysis) |
| 手部追踪 | MediaPipe Tasks Vision (HandLandmarker) |
| 颜色检测 | OpenCV 4.x (HSV + 连通域) |
| 音频引擎 | Oboe (AAudio) → SoundPool 降级 |
| 持久化 | DataStore Preferences + kotlinx.serialization |
| 构建 | Gradle KTS + Version Catalog |

## 项目结构

```
app/src/main/java/com/electrodig/voidmusic/
├── audio/          # 音频引擎 (Oboe JNI / SoundPool / WAV解码 / Transport时钟)
├── camera/         # CameraX 封装 (取景器 / 帧路由)
├── detection/
│   ├── color/      # OpenCV HSV 颜色分割
│   ├── grid/       # 透视网格 / 序列器状态
│   ├── hand/       # MediaPipe 手部追踪
│   └── hit/        # 击打检测 / 仲裁 / 鼓垫追踪
├── persistence/    # 设置持久化 & 性能档位配置
├── session/        # ViewModel & UI 状态
└── ui/
    ├── screens/    # 主屏幕 / 设置 / 引导
    ├── components/ # HUD / 叠加层 / 控件
    └── theme/      # Material 3 主题
app/src/main/cpp/   # Oboe 原生音频 (CMake)
app/src/main/assets/ # MediaPipe 模型
app/src/main/res/   # 资源 (鼓声样本 / 图标)
```

## 构建

### 前置条件

- Android Studio Meerkat (2024.3+) 或更高
- JDK 21 (随 Android Studio 内置)
- 如需原生音频：SDK Manager 安装 NDK 27.2.12479018

### 命令

```bash
# Debug 构建 (含所有 ABI，适合模拟器测试)
./gradlew assembleDebug

# Release 构建 (仅 arm64-v8a，APK ~44MB)
./gradlew assembleRelease

# 运行单元测试
./gradlew testDebugUnitTest
```

### 启用原生音频 (Oboe)

在 `gradle.properties` 中设置：
```properties
enableNativeBuild=true
```
需先通过 SDK Manager 安装 NDK 27.2.12479018。未启用时自动使用 SoundPool 降级方案。

### 签名 Release 与自动发布

Release 构建必须提供专用签名，不会回退生成可误传的 unsigned 候选包。本地复制
`keystore.properties.example` 为未跟踪的 `keystore.properties`，填写仓库外的
keystore、密码文件与 alias 路径后运行：

```bash
./gradlew -PenableNativeBuild=true assembleRelease
bash scripts/prepare_release.sh \
  app/build/outputs/apk/release/app-release.apk \
  release-dist \
  v0.1.0-m8
```

GitHub Actions Release 作业需要配置以下 Repository Secrets：

- `ANDROID_RELEASE_KEYSTORE_BASE64`
- `ANDROID_RELEASE_STORE_PASSWORD`
- `ANDROID_RELEASE_KEY_ALIAS`
- `ANDROID_RELEASE_KEY_PASSWORD`

推送与 APK `versionName` 一致的标签（如 `v0.1.0-m8`）后，工作流会自动完成签名、
证书/版本校验、SHA-256 生成，并创建普通公开 GitHub Release。内测里程碑不标记为
Latest；进入稳定期后再加入三档设备验收硬门禁。正式 keystore 一旦用于分发，后续
覆盖升级必须持续使用同一证书，并在加密离线位置保留至少一份备份。

## 性能档位

| 档位 | 相机分辨率 | 分析帧率 | MediaPipe | 手部数 | 降采样 |
|------|-----------|---------|-----------|-------|--------|
| HIGH | 640×480 | 30 FPS | GPU | 2 | 1x |
| MEDIUM | 640×480 | 20 FPS | GPU | 2 | 0.5x |
| LOW | 480×360 | 15 FPS | CPU | 1 | 0.25x |

省电模式或设备过热时自动切换至 LOW 档位。

> 分析分辨率只需略大于模型输入（192×192 / 224×224），过高分辨率浪费拷贝/旋转/推理带宽。预览取景器走独立 Preview use case 保持清晰。

---

## 技术原理

### 整体数据流

```
CameraX ImageAnalysis (传感器原生方向, rotationDegrees=D)
  → FrameRouter.analyze [单线程后台, STRATEGY_KEEP_ONLY_LATEST]
    → image.toBitmap()                        [RGBA, 传感器空间, 不旋转]
    → rotateBitmapForDisplay(raw, D)          [→ 屏幕方向; 0° 零开销]
    → consumer[0] HandTracker.detectAsync()   [异步, 不阻塞]
    → consumer[1] ColorSegmenter.segment()   [同步 OpenCV, 每 3 帧]
  → MediaPipe 异步回调
    → OneEuroHandStabilizer.smooth()          [低通滤波: 平滑 → overlay]
    → _hands (平滑) / _rawHands (原始)        [双 StateFlow]
  → Compose collectAsState → HandOverlay Canvas 重绘
```

### 物体识别（HSV 颜色分割）

**管线**：`Bitmap → BGR Mat → HSV → inRange 阈值 → 形态学开闭 → findContours → 面积/长宽比过滤 → DrumZone`

**HSV 预设**（OpenCV H∈0..180, S/V∈0..255）：

| 预设 | H 范围 | S 最低 | V 最低 | 鼓垫 | 说明 |
|------|--------|-------|--------|------|------|
| 红 | 160~8 (wrap) | 100 | 50 | KICK | 跨 0/180 边界，wrap 拼接 160..180 + 0..8 两段 |
| 蓝 | 95~130 | 80 | 50 | SNARE | 纯蓝范围 |
| 绿 | 45~85 | 80 | 50 | CLAP | 两侧与黄(35)/蓝(95)留间隙 |
| 黄 | 22~35 | 80 | 50 | TOM | 窄范围，集中在标准黄色 H≈28 |

**红色 hue wrap 处理**：红色横跨 HSV 色相环的 0/180 边界。当 `hMin > hMax` 时，`ColorSegmenter.threshold` 拼接两段 `inRange`：
- 段 1：`H = hMin..180`（如 160..180，品红/暗红侧）
- 段 2：`H = 0..hMax`（如 0..8，纯红侧）
- 两段 `bitwise_or` 合并

**肤色过滤**：
- S 门槛 100（红色）/80（其他）：肤色 S 通常 50~90，被滤除
- minAreaFraction 0.001：手部关节/指甲等小色斑面积不够被滤除

**降频优化**：ColorSegmenter 每 3 帧执行一次（物件空间变化慢），hit 检测每帧执行用缓存 zones，避免 OpenCV 阻塞 hand 路径导致 FPS 下降。

### 手部识别（MediaPipe HandLandmarker）

**模型**：`hand_landmarker.task`（7.8MB，含两个 TFLite）
- `hand_detector.tflite` — 输入 192×192，检测手部位置
- `hand_landmarks_detector.tflite` — 输入 224×224，输出 21 个关键点

**运行模式**：`LIVE_STREAM` + `detectAsync`，GPU 优先（Delegate.GPU），失败降级 CPU。

**坐标系**：归一化 [0,1] 相对旋转后 bitmap（屏幕方向），x=水平、y=垂直。

**双路径输出**（关键设计）：
- `hands`（平滑）→ overlay 叠加层：OneEuro 低通滤波，骨架稳定不抖
- `rawHands`（原始）→ hit 检测：未经滤波，保留 tap 瞬间位移尖峰

> 为什么分离？OneEuro 滤波器为 overlay 稳定而设计，但会削平 tap 的瞬间高速位移。如果 hit 路径也用平滑值，tap 速度被稀释到阈值以下，永远触发不了。分离后两条路径各取所需：overlay 要稳，hit 要快。

### tap 触发检测

**HitDetector** — 从指尖轨迹检测 tap 手势：

- **位移阈值**（非速度）：用 window 峰值位移 `max(hypot(Δx, Δy))`，阈值 0.03（归一化单位，~3% 帧宽）
- **峰值提取**（非平均）：tap 只占 1 帧，平均会被相邻静默帧稀释；取 window 内最大 inter-frame 位移
- **任意方向**：合速度 `hypot(dx, dy)`，适配俯视/侧视/斜视各角度（原来只认正 Y 向下，侧视横向 tap 无法触发）
- **低帧率鲁棒**：位移与 dt 无关，11 FPS 和 30 FPS 下同样的 tap 位移都能触发（速度 = 位移/dt 会被长 dt 稀释）

**HitArbiter** — 将候选映射到鼓区：
- `PadTracker.locate` — box 容差 0.12 + 中心吸引半径 0.15，匹配 fingertip 到最近 zone
- `retriggerCooldownMs = 90ms` — 同一 zone 重复触发冷却
- `mapVelocity` — 位移→0..1 增益，软饱和

### 相机旋转处理

`ImageProxy.toBitmap()` 返回**传感器原生方向** bitmap（不旋转）。`FrameRouter` 统一按 `imageInfo.rotationDegrees` 旋转后分发，所有下游消费者共享屏幕坐标系。

`CameraModule` 保留 Preview/ImageAnalysis 引用，`updateTargetRotation()` 在设备旋转时（`DisplayManager.DisplayListener`）同步 use case 的 `targetRotation`，无需重绑。

### 相机绑定（响应式）

`camera` 和 `handTracker` 以 `settings.performanceLevel` 为 remember key。settings 从 DataStore 异步加载，可能导致实例重建。绑定改为 `LaunchedEffect(camera, lifecycleOwner, previewView)` 响应式：实例重建时自动 unbind 旧的 + 重绑新的，避免"绑定的相机"与"被收集的 tracker"是两个不同实例。

## 隐私

- 不申请 `INTERNET` 权限
- 摄像头画面不写入磁盘、不上传
- 所有处理（视觉识别 + 音频）均在本地完成

## License

Proprietary — Electro-Dig.
