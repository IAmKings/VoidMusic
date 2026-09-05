# 技术设计

## 设计目标

把实时演奏路径从 Compose 页面生命周期中分离出来，以明确的状态机管理相机、识别和音频资源；把高频路径中的共享可变状态和重复计算替换为不可变快照与按需更新。

## 模块边界

### LivePerformancePipeline

负责：

- CameraModule、FrameRouter、HandTracker、ColorSegmenter、ZoneTracker 和 TapHitProcessor 的生命周期。
- TAP/STEP 模式切换和跨线程只读快照发布。
- 向界面提供统一 PerformanceState 和 PerformanceMetrics。
- 把有效击打发送给 DrumEngine。

不负责：

- Compose 绘制、导航和权限请求。
- 设置持久化。
- 具体音频解码或原生回调混音。

建议最小接口：

```kotlin
interface LivePerformancePipeline : AutoCloseable {
    val state: StateFlow<PerformanceState>
    val metrics: StateFlow<PerformanceMetrics>
    fun start(session: PerformanceSession)
    fun suspend()
    fun update(config: PerformanceConfig)
    fun setMode(mode: StudioMode)
    override fun close()
}
```

start、suspend 和 close 必须幂等。CameraModule 使用 generation token 丢弃旧的 ProcessCameraProvider 回调，并在最终 close 时关闭 Executor。

### GridProjection

GridProjection 是由四个校准点派生的不可变值，包含：

- 校准版本或角点值。
- 正向、反向透视变换所需数据。
- 4 × 16 个已映射格子中心。
- point → cell 的无额外校准更新查询。

CalibrationOverlay 只产生新的角点；投影在角点变化时统一计算。StepSequencerOverlay 和分析线程只读取发布后的快照。

### AudioRuntime

原生层向 Kotlin 层发布：

```text
Stopped → Starting → Running
                    ↓
               Disconnected → Reopening → Running
                                      └→ Fallback
```

- 原生实时回调只消费预分配数据，不执行重建、日志格式化或锁等待。
- 非实时线程执行 stream 重建和后端切换。
- Voice 满载时使用轮转或年龄信息选择抢占对象。
- PCM 音色优先缓存后切换，避免主线程同步重复解码。

### Transport 与 SequenceState

- 64 个步进格内部使用 Long 位图；序列化边界继续兼容现有二维布尔结构。
- Transport 注入 MonotonicClock 和 AudioTrigger 接口，便于纯 JVM 测试。
- 每拍使用 `nextDeadline += interval`，延迟到绝对截止时间；过期时按明确策略跳过或对齐，不累计工作耗时。

### RuntimePerformancePolicy

```text
effectiveLevel = min(preferredLevel, batteryConstraint, thermalConstraint)
```

- preferredLevel 持久化。
- batteryConstraint、thermalConstraint 仅存在于运行期。
- PowerManager 监听器和广播接收器均有对称注销。

## 线程与数据所有权

- 主线程：Compose 状态、用户配置和生命周期事件。
- 相机分析 Executor：帧转换、色块分割和 STEP 原始手部帧消费。
- MediaPipe 回调线程：生成不可变 HandFrame，并执行 TAP 快速路径。
- 原生音频回调：只读取 PCM 和无锁触发队列。
- 跨线程共享只允许不可变对象、AtomicReference 或有界队列；Mat 和 MutableList 不跨线程共享。

## 图像分配策略

先建立每分钟分配量、GC 次数和分割耗时基线。优化顺序：

1. 移除 GridScanner 的确定性 Mat 热点。
2. 用可复用 Mat 完成色块缩放和阈值工作区。
3. 在验证 MediaPipe 异步消费结束点后，再考虑 Bitmap/MPImage 池化或显式释放。
4. 根据场景稳定度降低色块分割频率，配置变化和场景变化立即刷新。

## 兼容与迁移

- SettingsRepository 的现有序列化字段保持可读。
- SequenceState 位图仅作为运行时表示，保存时转换为现有格式；待单独版本化后才调整磁盘结构。
- 保留 Room 依赖，但移除 SessionViewModel 启动时的 `seedBuiltIns()`；内置音色继续由 BuiltInKits 提供。
- 当前仅保存内置元数据摘要的 Room 模型不作为未来音频库模型直接扩展。
- 下一任务以 Room 保存音色、音频资产、鼓垫映射和校验状态，以应用专属文件目录保存音频内容。
- 下一任务把 `activeKitIndex` 迁移为稳定的 `activeKitId`，并建立数据库 Schema、音频资产格式和设置三类独立迁移契约。
- 详细设计与实施约束见 `research/audio-import-storage-and-migration.md`。

## 回滚策略

- 每个实施阶段独立提交，不跨阶段混合重构和行为调整。
- 新 Pipeline 可先由 MainScreen 适配并保持旧输入输出，再删除旧编排代码。
- 音频恢复失败时保持现有 SoundPool 后备路径。
- 自适应分割通过运行时开关保留固定频率回滚能力，完成真机数据验证后再移除开关。
