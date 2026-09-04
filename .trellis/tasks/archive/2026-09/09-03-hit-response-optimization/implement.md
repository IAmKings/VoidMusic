# 击打触发手感优化 — 实施计划

1. 提取不可变 `HitSnapshot` 与顺序命中消费者；以原始 normalized landmarks 直接完成 Tap 模式的命中和事件发布，增加 callback→consume、结果/鼓区年龄保护。
2. 改造 `HitDetector` 为持久记录抬手的低延迟 armed/rearm 状态机，补充 94–167 ms 连击、提前抬手、持续下降、回弹、跨色块与双手换序测试。
3. 将手部源帧新鲜度改为 140 ms 理想目标、近期推理基线 + 40 ms 余量、260 ms 硬上限的自适应门限；鼓区保持 260 ms 硬上限，覆盖约 16 FPS 的三帧分割周期，并补充突发陈旧、持续高延迟和硬边界测试。
4. 落地 0.50 / 60 ms / 70 ms 默认调音与旧默认配置迁移；保留用户可调范围。
5. 在 native 侧公开丢弃触发计数，在 `DrumEngine` 与 HUD 展示后端/计数；补充边界测试。
6. 执行单元测试、Lint、debug 构建；PJZ110 安装后按验收场景采集新 HUD 指标。

## 验证

- `./gradlew -PenableNativeBuild=true :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon --max-workers=1`
- `adb install -r -t app/build/outputs/apk/debug/app-debug.apk`
- 真机：静态四色物品、94–167 ms 同色连击与四色轮击、持续下压、双手交替、步进模式、后台返回。
