# 击打触发手感优化 — 设计

## 命中快速通道

`HandTracker` 保留对 UI 的平滑 StateFlow，同时在 MediaPipe 结果回调中把原始 `TimestampedHands` 直接交给 `TapHitProcessor`。处理器用 `@Synchronized` 保证 `HitDetector` 状态顺序更新，并只读取原子的 `HitSnapshot`（规范化鼓区及其时间戳）；当前模式、音频引擎和触觉设置通过独立原子引用读取。快照由相机线程或 Compose effect 更新，回调线程不读取 Compose snapshot。

MediaPipe `LIVE_STREAM` 自身丢弃被更新输入，结果回调没有额外命中队列积压。Tap 模式直接用原始 normalized landmarks 和 normalized drum zones 判定并提交音频，无需等待 OpenCV 分割或 Preview 坐标映射；步进模式仍维持原相机线程实现，避免同时变更两种交互语义。回调完成→消费只作为观测指标，不再误当作结果总年龄。

## 手势状态机

每个手指轨迹维护 `armed`、`liftObserved`、上一次 sample、最近触发时间和触发位置：

1. 向下速度首次超过阈值且 `armed` 时立即触发，保证低延迟。
2. 触发后 `armed=false`，后续持续下移不会重复出候选。
3. 检测到向上速度，或从触发位置回弹达到最小距离后，将 `liftObserved=true`；即使此时仍在最短间隔内，也不得遗忘该事实。
4. `liftObserved` 已成立且最短间隔结束后重新 `armed=true`；默认间隔 60 ms、最小回弹距离 0.018。
5. 轨迹匹配半径调整为 0.32，并以 MediaPipe handedness 约束双手归属，减少快速跨色块时重新建档。

候选速度仍使用当前差分，不为“确认减速”增加未来帧等待；后续可基于采集数据引入短窗口鲁棒速度估计。

## 新鲜度与音频观测

`HitSnapshot` 包含 `zoneTimestampMs`。手部新鲜度以源帧到消费的总年龄判断：140 ms 是理想目标，处理器用近期推理年龄的指数估计加 40 ms 抖动余量形成自适应预算，并以 260 ms 为不可突破的硬上限。这样会拒绝突发陈旧帧，但不会在设备持续运行后因推理基线达到 186–208 ms 而永久静音。callback 完成时间只用于观测回调→消费耗时。结果被消费时鼓区快照超过 260 ms 同样跳过。异步推理结果的源帧可以早于最新鼓区帧，因此不能用两者的源时间差判断鼓区有效性；静态物品在当前分割频率下保持连续可击打，快速移动的物品仍受明确上限保护。

默认调音版本随 `Settings` 持久化。缺少版本字段的旧配置迁移到速度阈值 0.50、复位 60 ms；之后用户修改的当前版本配置保持不变。

JNI 暴露 `nativeDroppedTriggerCount()`，读取原子计数；`DrumEngine` 提供只读 diagnostics，HUD 每秒刷新。它不改变触发队列的实时行为。

## 风险与回退

- 命中快速通道必须单线程串行处理，不能让多个 MediaPipe callback 并发改变 `HitDetector` 状态。
- 规范化坐标是命中通道唯一坐标系；UI 映射继续在显示层完成。
- 新参数必须以 90、120、140、160 BPM 的合成节奏和真机快速连击确认；错误体验可整体回退调音版本。
