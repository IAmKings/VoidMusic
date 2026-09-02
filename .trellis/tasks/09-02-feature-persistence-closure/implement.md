# 核心功能与持久化闭环 — 执行计划

1. 定义可序列化的会话 DTO、DataStore 键和 Room Kit schema/DAO，先写迁移和恢复测试。
2. 扩展 SessionViewModel 与设置 UI，接入全部参数和有确认的重置流程。
3. 实现取色帧快照、HSV buffer、边界校验和即时分割更新。
4. 添加第二套真实差异 Kit；接入音频任务的 `setKit` 契约并测试实际生效。
5. 持久化模式、BPM、序列、校准和检测配置；重启回归测试。
6. 与视觉稳定 ID/velocity 和音频 API 集成，完成真机功能走查。

## Validation

- [x] `./gradlew :app:testDebugUnitTest :app:lintDebug` — 60 项 JVM 测试与静态检查通过（2026-09-02）。
- [x] `./gradlew :app:lintDebug :app:assembleDebug` — 通过；仅保留既有弃用提示。
- [ ] 真机验证取色、两套 Kit、设置恢复和重置确认。
- [x] `KitDaoTest` 在 `PJZ110 / Android 16` 真机通过：插入、读取、替换、删除 CRUD（2026-09-02）。
- [x] 新增会话 JSON round-trip 与兼容旧字段回归测试。

## Implemented

- 取景器的“从取景器取色”进入点按状态；下一帧以 `PreviewCoordinateMapper` 反向映射触点，在小范围 HSV 采样后更新当前预设。红色跨 0/180 色相边界与 HSV 值域均有 JVM 测试。
- 内置 Kit 已从 1 套扩展到默认套鼓与电子打击两套；选择变化会调用 `DrumEngine.setKit`，重载实际 pad→sample 映射，而不仅持久化索引。
- 修复 SoundPool 备用后端的加载计数，确保所有样本加载后能够进入可播放状态。
- 设置页新增击打速度阈值、防抖、手部平滑参数并实时连至检测层；恢复默认设置现在要求明确确认。
- 模式、BPM、4×16 序列与四点校准现以 `Settings` 的可序列化 DTO 存储。冷启动先等待 DataStore 真正加载，随后才恢复/保存，避免默认状态覆盖既有会话；旧 JSON 缺字段会安全回退到默认值并有 round-trip 回归测试。
- Room `KitDatabase` / `KitDao` / `KitRepository` 已建立，Kit 元数据与 `res/raw` PCM 分层保存；SessionViewModel 启动时幂等写入内置目录，数据库版本为 1。

## Rollback

数据库迁移与 DataStore schema 保持向后兼容；新字段始终有安全默认值。
