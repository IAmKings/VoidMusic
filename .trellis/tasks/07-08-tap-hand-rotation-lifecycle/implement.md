# Implement — tap 模式手部识别生命周期与骨架 90 度朝向

## Execution Checklist (ordered)

### Step 1 — 旋转助手 + 单测（先建可验证基础）

- [x] 1.1 在 `camera/FrameRouter.kt` 新增 `internal fun rotateBitmapForDisplay(bitmap, degrees): Bitmap`
  - 0° (mod 360) 原样返回，避免分配
  - 否则用 `Matrix.postRotate` 生成新 bitmap
- [x] 1.2 新增 `app/src/test/java/com/electrodig/objectdrumstudio/camera/FrameRouterTest.kt`
  - 抽出纯函数 `normalisedRotationDegrees` 以绕过纯 JVM 测试无法用 Bitmap 的限制
  - 覆盖 0/90/180/270/360/450/720/-90/-180/-270/-360 全部边界
- [x] 1.3 跑 `./gradlew :app:testDebugUnitTest --tests "*FrameRouterTest*"` 通过

### Step 2 — FrameRouter 接入旋转

- [x] 2.1 在 `FrameRouter.analyze` 中：`image.toBitmap()` → `rotateBitmapForDisplay(raw, image.imageInfo.rotationDegrees)` → 分发给 consumers
- [x] 2.2 确认 `imageProxyConsumer` 仍在 finally 中关闭原 proxy
- [x] 2.3 `./gradlew :app:compileDebugKotlin` 通过

### Step 3 — CameraModule targetRotation 支持

- [x] 3.1 `CameraModule` 增加 `private var analysis: ImageAnalysis?` 与 `private var preview: Preview?`，在 `startPreview` 绑定后赋值
- [x] 3.2 `startPreview` 用 `previewView.display?.rotation ?: Surface.ROTATION_0` 设 `ImageAnalysis` 与 `Preview` 的初始 `targetRotation`
- [x] 3.3 新增 `fun updateTargetRotation(rotation: Int)`：同步更新两个 use case 的 `targetRotation`
- [x] 3.4 `stop()` 中清空引用
- [x] 3.5 `./gradlew :app:compileDebugKotlin` 通过

### Step 4 — MainScreen 响应式绑定 + DisplayListener

- [x] 4.1 `CameraPreview` 的 `onPreviewViewReady` 调用点改为只存 PreviewView 到 `var previewView by remember { mutableStateOf<PreviewView?>(null) }`（不在 factory 内绑定）
- [x] 4.2 新增 `DisposableEffect(camera) { onDispose { camera.stop() } }` — camera 重建时 unbind 旧实例
- [x] 4.3 新增 `LaunchedEffect(camera, lifecycleOwner, previewView)` — 三者就绪后 `camera.startPreview(lifecycleOwner, pv)` + `viewModel.setCameraReady(true)`
- [x] 4.4 移除原 `CameraPreview(onPreviewViewReady = { pv -> camera.startPreview(...); viewModel.setCameraReady(true) })` 中的直接绑定
- [x] 4.5 新增 `DisposableEffect(Unit)` 注册 `DisplayManager.DisplayListener`：`onDisplayChanged` 时 `camera.updateTargetRotation(previewView?.display?.rotation)`；`onDispose` 注销
- [x] 4.6 `./gradlew :app:compileDebugKotlin` 通过

### Step 5 — 全量验证

- [x] 5.1 `./gradlew :app:testDebugUnitTest` 全通过
- [x] 5.2 `./gradlew :app:compileDebugKotlin` 通过
- [x] 5.3 `./gradlew :app:lint` 无新增 error
- [ ] 5.4 真机验证（AC1-AC6）：需用户在真机执行

## Validation Commands

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugKotlin
./gradlew :app:lint
```

## Review Gates

- Step 1 后：旋转助手单测绿（纯逻辑可独立验证）。
- Step 4 后：编译通过，准备真机验证。
- Step 5：全部 AC 达标。

## Rollback Points

- 任意步骤失败：`git checkout -- app/src/main/java/com/electrodig/objectdrumstudio/camera/ app/src/main/java/com/electrodig/objectdrumstudio/ui/screens/MainScreen.kt` 并删除新增测试文件。
- Step 3/4 若相机绑定改动引发回归，可单独回滚 Step 4 保留旋转修复（Bug A 仍被修复，Bug B 暂留）。
