# 视觉性能优化 — 实施计划

1. 为 `FrameRouter` 增加 deadline/相位型节流状态，先扩展纯逻辑测试覆盖 30→20 和 60→20。
2. 扩展时间戳手部结果与 `VisionMetricsRecorder`，采集 callback 和消费时间；在 HUD 显示分段 P50/P95。
3. 将 `ColorSegmenter` 改为 RGBA→HSV 与工作 Mat/mask 复用，保留红色 Hue 环绕和关闭语义。
4. 运行 JVM 测试、Lint、debug 构建；安装 PJZ110，采集同场景前后数据并检查日志。

## 验证命令

- `./gradlew -PenableNativeBuild=true :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon --max-workers=1`
- `adb install -r -t app/build/outputs/apk/debug/app-debug.apk`
- 真机：固定红/蓝/绿/黄物品，单手连续击打，记录 HUD 的全部 P50/P95 与近期日志。

## 审查点

- 调度测试先于实现完成，防止把 20 FPS 再量化为 15 FPS。
- OpenCV Mat 的分配/释放、错误路径和 size 变化必须一并检查。
- 只提交与本任务相关的相机、色块、手部、HUD、测试和任务文件；研究报告作为任务输入一并保留。
