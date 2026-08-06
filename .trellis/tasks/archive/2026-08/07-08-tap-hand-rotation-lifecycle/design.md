# Design — tap 模式手部识别生命周期与骨架 90 度朝向

## Scope & Boundaries

涉及层：相机层（`camera/`）、检测层（`detection/hand`、`detection/color`、
`detection/hit`）、UI 层（`ui/screens/MainScreen`、`ui/components`）。

**改动边界**：
- `camera/FrameRouter.kt` — 新增旋转助手 + 在分发前旋转 bitmap。
- `camera/CameraModule.kt` — 保留 use case 引用，支持 `targetRotation` 更新。
- `ui/screens/MainScreen.kt` — 相机绑定改为响应式 effect + DisplayListener。
- 新增 `app/src/test/.../camera/FrameRouterTest.kt` — 旋转助手单测。

**不改**：领域模型（`Hand`/`DrumZone`/`NormalizedLandmark`）、`HandTracker`、
`ColorSegmenter`、`HitDetector`、`HandOverlay`、`DrumZoneOverlay` 的内部逻辑——它们
消费的已是「旋转后、屏幕方向一致」的归一化坐标，旋转在更上游统一完成。

## Root Cause (confirmed via code read)

### Bug A — 骨架 90° 交叉（竖屏与横屏均错）

`FrameRouter.analyze`（`camera/FrameRouter.kt:31`）调用 `image.toBitmap()` 得到的是
**传感器原生方向**的 bitmap（多数手机传感器横向布局），未应用
`image.imageInfo.rotationDegrees`。MediaPipe 在该未旋转 bitmap 空间返回归一化坐标，
而 `HandOverlay`（`ui/components/HandOverlay.kt:40`）按屏幕方向 `Offset(it.x*w, it.y*h)`
绘制 → 竖屏正好差 90°。

同一未旋转 bitmap 也喂给 `ColorSegmenter`（`detection/color/ColorSegmenter.kt:111`），
鼓区归一化同样偏 90°。

**命中检测连带失效**：`HitDetector`（`detection/hit/HitDetector.kt:53,82`）以「正 Y =
向下敲击」判定 tap。图像旋转 90° 后，真实向下的敲击在传感器空间是正 X，
`downwardSpeed` 恒为 0 → tap 打不出鼓（不只是画歪）。

**横屏为何也一样**：`AndroidManifest.xml:36` 用
`configChanges="orientation|screenSize|..."` 自处理旋转、未锁方向，但
`CameraModule.startPreview` 只在首次绑定时设定 use case，`ImageAnalysis`/
`Preview` 的 `targetRotation` 固化为绑定时的方向。设备转横屏后 Activity 不重建、
相机不重绑，`rotationDegrees` 仍是竖屏值 → 横屏依旧错位。所以光旋转 bitmap 不够，
还要让 use case 的 `targetRotation` 跟随显示方向。

### Bug B — 进设置再返回才出现手部识别

`MainScreen.kt:164` 的 `camera = remember(settings.performanceLevel){ CameraModule(
FrameRouter{ handTracker.detect(...) }) }` 与 `:94` 的
`handTracker = remember(settings.performanceLevel){...}` 均以 `settings.performanceLevel`
为 remember key。

`settings` 来自 `SessionViewModel.kt:38`
`repo.settings.stateIn(viewModelScope, Eagerly, Settings.DEFAULT)`：先发 `DEFAULT`
（MEDIUM），DataStore 异步加载后再发持久化真实值。若持久化的 `performanceLevel`
≠ MEDIUM（用户改过性能档），`camera`/`handTracker` 被重建为**实例 #2**。

但相机只在 `MainScreen.kt:240` 的 `onPreviewViewReady`（`CameraPreview` 的
AndroidView `factory`，**只触发一次**）里绑定了**实例 #1**。UI `collectAsState`
收的是实例 #2 的 `hands`（恒空），而绑定相机把帧喂给实例 #1 → 永远看不到手。

进入设置再返回时，Navigation Compose 销毁并重建 MainScreen 组合，
`onPreviewViewReady` 在 settings 已稳定后**再次**触发 → 绑定的相机与被收集的
tracker 终于是同一实例 → 手部识别出现。

## Solution Design

### Fix A — 统一旋转（FrameRouter 层）

在 `FrameRouter.analyze` 中，按 `image.imageInfo.rotationDegrees` 旋转 bitmap 后再
分发给所有 `bitmapConsumers`。这样 hand / zone / hit / overlay 全部回到屏幕坐标系，
一处改动修四个下游。

**纯函数助手**（可单测，0° 时原样返回避免分配）：

```kotlin
internal fun rotateBitmapForDisplay(bitmap: Bitmap, degrees: Int): Bitmap {
    if (degrees % 360 == 0) return bitmap
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
```

在 `analyze` 内：
```kotlin
val raw = runCatching { image.toBitmap() }.getOrElse { ...; return }
val bitmap = rotateBitmapForDisplay(raw, image.imageInfo.rotationDegrees)
try { bitmapConsumers.forEachIndexed { ... consumer(bitmap, image) } }
finally { imageProxyConsumer(image) }
```

注：`image.toBitmap()` 对 RGBA_8888 输出格式返回的 bitmap 旋转后仍是 RGBA，下游
`ColorSegmenter.bitmapToBgrMat` 与 `HandTracker`（`BitmapImageBuilder`）均兼容。
旋转产生的新 bitmap 由 consumer 使用、由 GC 回收（单帧量级，与现有 downsample
`createScaledBitmap` 同生命周期模型，不显式 recycle 以避免与正在使用的 bitmap
冲突）。

### Fix B — targetRotation 跟随显示方向（CameraModule）

`CameraModule` 保留绑定的 `ImageAnalysis` 与 `Preview` 引用：

```kotlin
private var analysis: ImageAnalysis? = null
private var preview: Preview? = null
```

`startPreview` 中用 `previewView.display?.rotation ?: Surface.ROTATION_0` 设初始
`targetRotation`（`ImageAnalysis.Builder().setTargetRotation(...)` 与
`Preview.Builder().setTargetRotation(...)`）。

新增：
```kotlin
fun updateTargetRotation(rotation: Int) {
    analysis?.targetRotation = rotation
    preview?.targetRotation = rotation
}
```

CameraX 会在下个帧自动用新 rotation 产生 `imageInfo.rotationDegrees`，无需重绑。

### Fix C — 响应式相机绑定 + DisplayListener（MainScreen）

把 PreviewView 存入 state，用 effect 响应 `camera` 实例与生命周期：

```kotlin
var previewView by remember { mutableStateOf<PreviewView?>(null) }
CameraPreview(onPreviewViewReady = { previewView = it })

DisposableEffect(camera) {
    onDispose { camera.stop() }  // camera 重建时先 unbind 旧实例
}
LaunchedEffect(camera, lifecycleOwner, previewView) {
    val pv = previewView ?: return@LaunchedEffect
    camera.startPreview(lifecycleOwner, pv)
    viewModel.setCameraReady(true)
}
```

`CameraPreview` 的 `onPreviewViewReady` 改为只上报 PreviewView（不再在 factory 里
绑定），绑定交给上面的 `LaunchedEffect`。`camera` 重建（性能档变化）→
`DisposableEffect` unbind 旧实例 → `LaunchedEffect` 用新实例重绑。

**DisplayListener** 跟随旋转：
```kotlin
val context = LocalContext.current
DisposableEffect(Unit) {
    val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            val rot = previewView?.display?.rotation ?: return
            camera.updateTargetRotation(rot)
        }
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
    }
    dm.registerDisplayListener(listener, null)
    onDispose { dm.unregisterDisplayListener(listener) }
}
```

## Data Flow (after fix)

```
ImageProxy (sensor space, rotationDegrees=D)
  → FrameRouter.analyze
    → rotateBitmapForDisplay(raw, D) → bitmap (screen space)
    → consumer[0] HandTracker.detect(bitmap) → Hand (normalized to screen space)
       → HandOverlay: Offset(x*w, y*h) ✓ aligned
       → HitDetector: positive-Y = real downward tap ✓
    → consumer[1] ColorSegmenter.segment(bitmap) → DrumZone (normalized to screen space)
       → DrumZoneOverlay ✓ aligned
       → HitArbiter vs fingertip ✓ aligned
Display rotation change → DisplayListener → camera.updateTargetRotation(rot)
  → next frame's imageInfo.rotationDegrees updated → bitmap re-rotated accordingly
```

## Tradeoffs / Compatibility

- **旋转成本**：每帧 `createBitmap` 旋转一次。0° 时短路返回原 bitmap（零成本）。
  非零度时与现有 `ColorSegmenter` 的 `createScaledBitmap` 同为单帧 bitmap 分配，
  在 `STRATEGY_KEEP_ONLY_LATEST` 下可接受；若后续 profiling 显示瓶颈，可改用
  MediaPipe 的 `ImageProcessingOptions` 旋转或在 GPU 层处理（留作 M6 优化项）。
- **不显式 recycle 旋转 bitmap**：与项目现有 `createScaledBitmap` 用法一致；
  Android 8+ Bitmap 不需 recycle，GC 处理。
- **响应式绑定的首帧时机**：`LaunchedEffect(camera, lifecycleOwner, previewView)`
  在三者就绪后执行；previewView 由 AndroidView factory 异步上报，effect 会在
  state 变为非空时自动触发，覆盖了「settings 稳定晚于 factory」的时序。
- **向后兼容**：领域模型、单测 fixtures 不变；`HandTrackerTest`/`HitDetectorTest`
  构造的 `Hand` 仍是归一化坐标，旋转在更上游，测试无感知。

## Rollout / Rollback

- 单 PR，无 feature flag（本地优先应用，无服务端）。
- 回滚：`git checkout` 改动的 4 个文件 + 删除新增测试文件。
- 验证：AC7-AC9 自动化；AC1-AC6 真机验证（冷启动、竖/横屏、切档、旋转）。
