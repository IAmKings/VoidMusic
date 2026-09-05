# 实施计划

## 分支与提交策略

- 计划分支：`codex/runtime-performance-stability`
- 基线分支：`master`
- 每个阶段至少一个独立提交；阶段质量门失败时只回滚当前阶段。
- 不修改默认击打参数，不新增预发布版本或 CI 性能硬门禁。

## 阶段 0 — 基线冻结

- [x] 记录当前单元测试、Lint、Debug/Release 构建结果。
- [ ] 用同一套场景采集当前 FPS、手部分割、色块分割、缓存年龄、击打到出声、内存、GC 和温升。
- [x] 保存扬声器、耳机/蓝牙和返回扬声器的音频路由切换结果。
- [x] 保存后台 20 分钟恢复结果。
- [ ] 固定用于回归的色块、机位、光照、击打节奏和设备档位。

质量门：基线数据完整，现有连续击打与跨颜色切换正常。

## 阶段 1 — 稳定性闭环（P0）

### 1.1 生命周期治理

- [x] 为 CameraModule 区分暂停与最终关闭，使用 ExecutorService 并在 close 时 shutdown。
- [x] 增加相机异步绑定 generation token/closed guard。
- [x] 仅在权限有效且页面前台时启动 Pipeline；后台对称暂停。
- [x] Compose Flow 改为生命周期感知收集。
- [x] 增加重复 start/stop/close 和过期绑定测试。

### 1.2 GridProjection

- [x] 先增加四边形映射、边界和缓存失效测试。
- [x] 批量映射 64 个中心，移除逐点 Mat 创建。
- [x] CalibrationOverlay 绘制阶段禁止 setCalibration。
- [x] 用不可变 GridProjection 同时服务绘制与 STEP 命中定位。

### 1.3 音频恢复

- [x] 原生错误回调向非实时控制层发布断开事件。
- [x] 增加 Oboe 重建和 SoundPool 自动降级状态机。
- [x] 增加 backend、ready、xRun 和 droppedTriggers 指标。
- [x] 修复 Voice 固定抢占第一个实例的问题。
- [x] 真机验证扬声器、耳机/蓝牙和返回扬声器的切换。

### 1.4 遗留路径清理

- [x] 删除 MainScreen 中不可达的旧 TAP 分支和仅服务该分支的对象。
- [x] 确认 TapHitProcessor 是 TAP 模式唯一入口。

阶段质量门：生命周期、网格和音频恢复测试通过；真机连续演奏、后台恢复、路由切换通过。

## 阶段 2 — 架构与节拍一致性（P1）

### 2.1 抽取 LivePerformancePipeline

- [x] 建立 `LivePerformanceInputs` / `LivePerformanceEvent` 输入输出契约。
- [x] 迁移相机、识别、击打和指标编排，不改变算法参数。
- [ ] MainScreen 进一步收敛到权限、事件转发、音频/Transport 会话协调和渲染。
- [x] 合并重复的 AtomicReference/Flow 桥接，保留必要的不可变快照。
- [ ] 为深层 Pipeline 补充可替换帧源的集成测试夹具。

### 2.2 Transport 与序列状态

- [x] SequenceState 改为不可变内部表示并保持磁盘兼容。
- [x] 注入 MonotonicClock 和 AudioTrigger。
- [x] 改为绝对截止时间调度；过期拍直接跳过，禁止突发补打。
- [x] 增加 BPM 边界、播放/停止、清空、快照隔离、过期拍和长期无累计漂移测试。

### 2.3 运行时性能策略

- [x] 分离 preferredLevel 与 runtime constraints。
- [x] 使用 PowerManager thermal listener 并对称注销。
- [x] 用策略测试验证省电/温控解除后恢复用户首选档位。

### 2.4 持久化瘦身

- [x] 设置与 HSV 滑块使用本地预览并在拖动结束后落盘。
- [x] 合并重复 settings 数据源收集为 Loading/Ready/Error 状态。
- [x] 从 SessionViewModel 移除 KitRepository 和启动时 `seedBuiltIns()`，避免每次启动写入静态内置数据。
- [x] 保留 Room 依赖和数据库能力，不在当前任务内扩展音频导入 Schema。
- [x] 确认 BuiltInKits 仍是当前内置音色的唯一运行时来源。
- [x] 当前任务归档后，依据 `research/audio-import-storage-and-migration.md` 创建独立音频导入任务。

阶段质量门：MainScreen 职责收敛、Transport 测试通过、设置兼容、现有演奏回归无变化。

## 阶段 3 — 测量驱动性能优化（P2）

- [x] 为 FrameRouter 补充 consumer 异常、ImageProxy 必然关闭和生命周期测试。
- [x] 为 ColorSegmenter 增加真实 OpenCV 合成图测试。
- [x] 记录 Bitmap/MPImage 实际生命周期和分配热点。
- [x] 优先复用色块缩放 Mat、hierarchy 和阈值工作区。
- [x] 实现空闲低频、变化立即刷新的自适应色块分割。
- [x] 仅在确认异步消费完成点后实施 Bitmap/MPImage 复用或显式释放。
- [ ] 对比优化前后高/中/低三档真机指标。

阶段质量门：性能报告可复现；没有数据支持或导致首次识别变慢的优化不合入。

## 最终验证

```bash
./gradlew testDebugUnitTest lintDebug
./gradlew connectedDebugAndroidTest
./gradlew assembleRelease
```

- [ ] 签名 Release 覆盖首次启动、权限、引导、TAP、STEP、设置恢复。
- [ ] 四种声音连续快速切换无漏声、无明显过渡期。
- [x] 当前签名 Release 完成 5 分钟前台连续点击冒烟测试，无可感知异常。
- [x] 后台 20 分钟恢复无需重启。
- [x] 扬声器、耳机/蓝牙和返回扬声器的音频路由切换无需重启。
- [ ] 正式发布候选版完成前台连续演奏 20 分钟及高/中/低设备矩阵报告；当前阶段只报告，不作为 CI 硬门禁。
- [ ] 检查没有新增线程、Native stream、Bitmap、Mat 或接收器泄漏。

## 高风险文件与回滚点

- `ui/screens/MainScreen.kt`：先建立行为测试，再迁移编排；阶段 2 单独提交。
- `camera/CameraModule.kt`：异步绑定与 close 竞态；阶段 1.1 单独验证。
- `detection/grid/GridScanner.kt`：坐标方向错误会直接破坏 STEP 命中；以合成四边形测试保护。
- `audio/Transport.kt`：计时与状态结构同时变化，拆成不可变状态和调度两个提交。
- `main/cpp/drumengine.cpp`：原生实时线程禁止锁、阻塞和分配；保留 SoundPool 回退。
- `persistence/KitDatabase.kt`：当前任务不删除 Room；只解除启动写库耦合，正式模型留给下一任务实现。
