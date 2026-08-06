# 修复 tap 模式手部识别生命周期与骨架 90 度朝向

## Goal

修复 tap（鼓区）模式下两个相关缺陷，使手部骨架叠加层、命中检测、鼓区叠加在
竖屏与横屏下都与真实手部对齐，并让手部识别在冷启动后立即可用（无需先进入设置
再返回）。

## Background

用户报告：tap 模式下，进入设置页面再返回才会出现手部识别；且识别出的骨架与真实
手部存在 90 度交叉，横屏使用时同样错位。这使 tap 模式基本不可用——不仅叠加层画歪，
命中检测（依赖正 Y 向下敲击）也因坐标系旋转而失效，打不出鼓。

## Requirements

- R1 手部骨架叠加层在竖屏下与真实手部对齐（不再 90° 交叉）。
- R2 手部骨架叠加层在横屏下与真实手部对齐。
- R3 tap 模式命中检测在竖屏与横屏下都能正确识别向下敲击并触发鼓声。
- R4 鼓区（ColorSegmenter 产物）叠加层在竖屏与横屏下与真实物件对齐。
- R5 冷启动进入 MainScreen 后手部识别立即可用，无需先进入设置再返回。
- R6 切换性能档位（导致 `performanceLevel` 变化、`HandTracker`/`CameraModule`
  重建）后，相机与分析管线自动重新绑定，手部识别继续可用。
- R7 不引入新的相机卡顿/帧泄漏；`STRATEGY_KEEP_ONLY_LATEST` 下 ImageProxy 仍被
  正确关闭。
- R8 不破坏现有 M1（相机+手部）与 M3（命中+音频）流程；现有单测通过。

## Constraints

- 最小改动原则：优先在共享的 `FrameRouter` 层一次性旋转 bitmap，避免在每个下游
  consumer 各自处理旋转。
- 不改变 `Hand`/`DrumZone`/`NormalizedLandmark` 等领域模型的归一化坐标系约定
  （[0,1] 相对旋转后图像）。
- 保持 `configChanges="orientation|..."` 自处理旋转的 manifest 配置不变（不改为
  Activity 重建方式）。
- 不新增第三方依赖；仅用 Android `Matrix` / CameraX 现有 API。

## Acceptance Criteria

- [ ] AC1 竖屏：手部骨架叠加层与真实手部姿态一致，无 90° 偏移。
- [ ] AC2 横屏：手部骨架叠加层与真实手部姿态一致，无 90° 偏移。
- [ ] AC3 竖屏/横屏：向下敲击鼓区能触发对应鼓声并高亮该区。
- [ ] AC4 冷启动（已持久化非默认性能档位）：进入 MainScreen 后即显示手部骨架，
      无需进入设置再返回。
- [ ] AC5 在设置页切换性能档位后返回 MainScreen：相机与分析管线自动重绑，手部
      识别恢复。
- [ ] AC6 设备旋转（竖↔横）：叠加层实时跟随，无需重新进入页面。
- [ ] AC7 `./gradlew :app:testDebugUnitTest` 通过（含新增的 bitmap 旋转单测）。
- [ ] AC8 `./gradlew :app:compileDebugKotlin` 通过。
- [ ] AC9 `./gradlew :app:lint` 无新增 error。

## Notes

- 旋转修复在 `FrameRouter` 层完成，一处改动修正 hand / zone / hit / overlay 四个
  下游的坐标系。
- 生命周期修复把相机绑定从「一次性 AndroidView factory 回调」改为「响应 camera
  实例与生命周期」的 effect，并在 `camera` 重建时先 unbind 再重绑。
- 横屏支持需让 `ImageAnalysis` + `Preview` 的 `targetRotation` 跟随显示方向，否则
  `imageInfo.rotationDegrees` 会停留在绑定时的陈旧值。
