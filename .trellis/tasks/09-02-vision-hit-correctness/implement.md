# 相机坐标与击打链路正确性 — 执行计划

1. 抽出坐标 mapper，写 portrait/landscape、裁剪和旋转的失败测试。
2. 接入 PreviewView/CameraX 变换，迁移全部 overlay 和命中使用点。
3. 将 analyzer 对 Compose state 与 View 的直接访问改为线程安全事件流和 UI effect。
4. 实现 ZoneTracker，替换帧内递增 ID；为出现、遮挡、消失和重现写测试。
5. 用真实时间速度和向下方向重写 hit 候选；验证多手/多区和悬空情形。
6. 在竖横屏实体设备完成叠加与击打回归。

## Validation

- [x] `./gradlew :app:testDebugUnitTest --rerun-tasks` — 53 项 JVM 测试通过（2026-09-02）。
- [x] `./gradlew :app:lintDebug` — 通过；仅保留既有 Android/Gradle 弃用提示。
- [x] 调试包已安装至 `PJZ110`，应用前台启动并保持存活；主界面语义树显示相机 Surface、1 只手、9 个物件和 30 FPS（2026-09-02）。SurfaceView 受系统截图限制显示为黑色，不代表预览未运行。
- [ ] 用彩色目标物在竖屏、横屏各执行一次叠加/击打实测，并记录可接受偏差阈值。
- [ ] 在真机持续运行压力场景，确认无相机线程异常和事件丢失导致的可见问题。

## Implemented

- `PreviewCoordinateMapper` 按 `PreviewView.FILL_CENTER` 统一分析图、覆盖层与命中点坐标；手部、鼓区和 STEP 网格命中均使用映射后的坐标。
- 相机线程仅向有界 `MutableSharedFlow` 发布不可变事件；Compose 主线程更新 ViewModel、闪烁和触觉反馈。
- `ZoneTracker` 以预设、IoU 和中心距离跨帧维护 ID，TTL 为 500ms；分区冷却不再因逐帧重新编号失效。
- `HitDetector` 以相邻帧真实时间差计算向下速度，维持每根手指冷却并容忍 MediaPipe 手部列表顺序交换。

## Rollback

保留旧 mapper/命中逻辑直到新映射的自动化与真机验证均通过，再删除旧路径。
