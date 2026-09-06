# 技术设计

## 设计目标

以一个深层 `KitLibrary` 隔离 Storage Access Framework、文件系统、Room、设置迁移和可播放音色解析；实时音频层只接收已经验证和准备完成的音色，不接触 URI、DAO 或导入事务。

## 数据流与边界

```text
OpenDocument URI
  → AudioImporter（流式复制、大小限制、SHA-256）
  → WavValidator/Normalizer（内容校验、单声道、重采样、PCM16）
  → staging 文件
  → AssetStore（原子移动到 audio-assets）
  → Room 事务（AudioAsset + Kit + KitPadMapping）
  → KitLibrary 目录
  → PlayableKitLoader（后台读取/解码全部鼓垫）
  → DrumEngine 原子切换
```

边界约束：

- URI 只存在于导入入口，不持久化为实时播放依赖。
- `storageKey` 是数据库与文件系统之间的唯一持久边界，必须是受控相对键。
- WAV 结构只由一个解析器解释；校验、元数据和解码共享同一解析结果。
- Room 事务不承担文件 I/O；协调器显式补偿跨数据库/文件系统失败。
- UI 只消费 `KitSummary`、`ImportProgress` 和稳定错误类型。

## 领域模型

### 数据库

- `LibraryKitEntity`：`id`、`name`、`displayOrder`、`createdAt`、`updatedAt`。
- `AudioAssetEntity`：`id`、`storageKey`、`sha256`、原名、大小、帧数、采样率、声道、编码、`storageVersion`、`validationVersion`、状态和时间戳。
- `KitPadMappingEntity`：`kitId + pad` 联合主键，外键到 Kit 和 AudioAsset。

Room 只保存用户自定义音色；内置音色由 `BuiltInKits` 提供。`KitLibrary.kits` 将两者映射为同一个稳定 ID 目录。表内枚举使用稳定字符串，不使用 ordinal。

### 播放模型

把当前含糊的 `SampleRef(rawResId, isBuiltIn)` 替换为明确来源：

```kotlin
sealed interface AudioSampleSource {
    data class BuiltIn(val rawResId: Int) : AudioSampleSource
    data class Imported(val storageKey: String) : AudioSampleSource
}

data class PlayableKit(
    val id: String,
    val name: String,
    val samples: Map<DrumPad, AudioSampleSource>
)
```

DAO 实体不得跨过 `KitLibrary` 边界。`storageKey` 解析为绝对文件路径只发生在 `AssetStore`/loader 内部。

## KitLibrary 接口

```kotlin
interface KitLibrary {
    val kits: Flow<List<KitSummary>>
    suspend fun copyKit(sourceId: String, name: String): LibraryResult<String>
    suspend fun replacePad(kitId: String, pad: DrumPad, uri: Uri): ImportResult
    suspend fun renameKit(kitId: String, name: String): LibraryResult<Unit>
    suspend fun prepare(kitId: String): LibraryResult<PreparedKit>
    suspend fun deleteKit(kitId: String): DeleteResult
    suspend fun reconcile(): ReconcileReport
}
```

具体实现拥有 Room、`AssetStore`、解析器、调度器和时钟。Android URI 读取通过小接口注入，使核心导入流程可在 JVM 测试中使用内存/临时文件替身。

## 导入与提交状态机

```text
Copying → Validating → Normalizing → Moving → Committing → Ready
   └────────────── any failure ──────────────→ Cleanup → Failed
```

执行顺序：

1. 创建唯一 staging 文件，流式复制并累计原始大小和哈希。
2. 解析并验证 WAV，转换为规范化 PCM16 单声道 WAV；对规范化内容计算最终 SHA-256。
3. 以最终哈希查找 READY 资产；已存在则复用，否则把规范化文件原子移动到新 `storageKey`。
4. 在 Room 事务中更新资产和目标鼓垫映射。
5. 事务失败时删除本次新建的正式文件；无法删除则由 `reconcile()` 识别为无引用孤儿。
6. 无论成功失败都清理原始与规范化 staging 文件。

不创建缺鼓垫的可播放音色。`copyKit` 先把来源音色的五个映射复制到新音色：来源为自定义音色时复用现有 AudioAsset；来源为内置音色时，仅在用户执行复制时把五个内置 WAV 规范化并按哈希写入 AudioAsset，再建立完整映射。这样 Room 的映射始终只指向 AudioAsset，不需要把 Android 资源 ID 混入正式 Schema，也不会在应用启动时重复写库。

## 设置迁移

`Settings` 增加：

- `activeKitId: String?`
- `kitSelectionVersion: Int`

读取旧 JSON 时保留 `activeKitIndex` 作为迁移输入。若版本未升级：

1. `0 → default`、`1 → electro`。
2. 其他值回退 `default`。
3. 写入 `activeKitId` 与当前迁移版本。

新代码只按 ID 选择音色；旧 index 字段保留一个兼容周期但不再作为运行时来源。

## 音频准备与原子切换

- `PlayableKitLoader` 在后台为所有鼓垫读取并解码规范化文件/内置资源，生成 `PreparedKit`。
- `DrumEngine` 接收 `PreparedKit`，不得在 UI 调用内同步读盘。
- 新音色准备失败时不调用后端切换。
- 后端切换失败时尝试恢复旧 `PreparedKit`；恢复期间触发事件有界缓存，恢复后按现有策略处理。
- SoundPool 使用资源 ID 或受控文件路径加载；Oboe 使用预解码 PCM。两条路径消费同一个已验证来源模型。

## 删除与协调

- 删除音色事务解除映射并把无引用资产设为 `PENDING_DELETE`。
- 事务后删除文件；成功后删除资产记录，失败保留状态。
- `reconcile()` 清理超时 staging、重试 PENDING_DELETE、把缺文件的 READY 资产标为 BROKEN，并报告无引用孤儿。
- 若删除当前活动音色，先切换并持久化 `default`，再删除。

## 兼容、发布与回滚

- 新数据库使用 `void_music_library.db`，不迁移旧 `ods_kits.db` 的内置摘要。
- 新 Schema 从版本 1 开始并启用导出；后续每次修改必须附 Migration 和 `MigrationTestHelper` 测试。
- 功能通过开关式 UI 接入顺序逐步启用：Schema/迁移 → 导入核心 → 播放接入 → 管理 UI。
- 任一阶段失败可隐藏自定义音色入口，内置 `BuiltInKits` 与旧演奏链仍保持可用。

## 关键取舍

- 不长期依赖持久 URI：复制成本换取离线与权限稳定性。
- 不把 PCM 放 Room：避免数据库膨胀、Cursor/事务压力和实时路径耦合。
- 不直接扩展旧摘要库：当前没有用户数据，建立干净正式基线比永久迁移无价值结构更可靠。
- 不在 MVP 支持所有音频格式：先把一种可验证格式的端到端一致性做好。
