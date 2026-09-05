# 用户音频导入、校验与迁移模型建议

## 目的

本文作为 Void Music 完成“运行性能与稳定性优化”后的下一项开发参考，定义用户音频文件导入、校验、持久化和迁移的推荐方向。

本文记录架构决策与约束，不在当前任务中实现音频导入功能。

## 结论

保留 Room，但不原样保留当前数据模型。

- Room 保存结构化元数据和关系。
- 音频文件保存在应用专属持久目录，不保存为 Room BLOB。
- DataStore 继续保存体积很小的用户偏好，例如当前音色 ID。
- 内置音色继续由 `BuiltInKits` 静态提供，不在每次启动时写入数据库。
- 下一任务重新建立正式音色库 Schema，并从正式 Schema 开始执行非破坏性迁移。

## 当前代码事实

### 当前 Room 的实际用途

当前 `KitDatabase.kt` 只有一张 `kit_metadata` 表，保存：

- 内置音色 ID
- 名称
- 显示顺序
- 鼓垫到 `res/raw` 资源的诊断摘要

`SessionViewModel` 在初始化时调用 `seedBuiltIns()`，但生产播放链路和设置界面都直接读取 `BuiltInKits`，没有从 Room 恢复音色。因此当前启动写库没有运行时价值。

### 当前模型无法表达导入文件

- `SampleRef.rawResId` 是不可空 `Int`，只能稳定表达 `res/raw` 资源。
- `SampleRef.isBuiltIn` 是布尔标志，但没有外部文件位置、内容哈希、格式或校验状态。
- `Settings.activeKitIndex` 依赖列表位置；音色删除、插入和排序后可能指向错误对象。
- `DrumEngine.loadWav()` 只会从 `res/raw` 读取。
- `WavDecoder` 当前只支持 PCM 16-bit、单声道或双声道 WAV，且面向受控的内置文件，不足以直接处理任意用户文件。
- Room 当前 `exportSchema = false`，没有正式的 Schema 历史和迁移测试链。

## 存储职责划分

```text
Storage Access Framework
          │  用户选择源文件
          ▼
AudioImporter
  ├─ 临时复制
  ├─ 内容校验
  ├─ 解码与规范化
  ├─ SHA-256
  └─ 原子提交
          │
          ├──────────────→ filesDir/audio-assets/   音频文件
          │
          └──────────────→ Room                     元数据与关系

DataStore ───────────────→ activeKitId 等小型偏好
```

### Room 应保存

- 音色包身份、名称、类型和顺序。
- 音频资产身份、相对存储键、哈希、大小和格式信息。
- 音色包鼓垫与音频资产的映射。
- 校验版本、存储格式版本和可用状态。
- 创建、更新时间。

### Room 不应保存

- 音频文件二进制 BLOB。
- 解码后的完整 `FloatArray`。
- 设备绝对路径。
- 只在一次导入过程中有效的临时 URI。
- 可以由文件或代码重新推导的大块派生数据。

### 文件系统应保存

- 已通过校验、可供引擎加载的音频文件。
- 导入过程中的临时文件，但临时文件必须位于独立 staging 目录并能在失败后清理。

推荐默认使用内部 `filesDir/audio-assets/`，保证离线可用、访问稳定且无需额外存储权限。若未来需要跨应用共享或卸载后保留，应另行提供“导出音色包”，而不是让实时播放直接依赖外部 Content URI。

## 推荐数据模型

### KitEntity

```text
id              String / UUID，主键
name            用户可见名称
type            BUILT_IN 或 IMPORTED
displayOrder    排序
createdAt       创建时间
updatedAt       更新时间
```

内置音色不必每次启动写库。对外的 KitLibrary 可以把 `BuiltInKits` 与 Room 中的导入音色合并为同一个只读目录。

### AudioAssetEntity

```text
id                String / UUID，主键
storageKey        应用目录中的相对键，唯一
originalName      导入时的原文件名
sourceMimeType    文件选择器提供的 MIME，仅供诊断
sha256            完整内容或规范化内容哈希
byteSize          文件大小
frameCount        规范化后的帧数
sampleRate        采样率
channelCount      声道数
encoding          PCM16 等实际编码
storageVersion    资产磁盘格式版本
validationVersion 最近一次校验规则版本
status            READY、BROKEN 或 PENDING_DELETE
createdAt         创建时间
```

MIME 和扩展名不得作为可信校验依据，必须读取实际文件内容。

### KitPadMappingEntity

```text
kitId          外键 → KitEntity
pad            KICK/SNARE/CLAP/TOM/HIHAT
audioAssetId   外键 → AudioAssetEntity
```

以 `(kitId, pad)` 为联合主键，保证一个音色包的一个鼓垫只有一个有效映射。同一个 AudioAsset 可以被多个音色包复用。

当前不增加标签、收藏、云同步、导入任务表或多层音效参数；这些能力没有现阶段需求支撑。

## 推荐深模块

对界面和播放链路暴露一个小型 `KitLibrary` interface，DAO、文件路径、事务和校验细节全部保留在 implementation 内部：

```kotlin
interface KitLibrary {
    val kits: Flow<List<KitSummary>>
    suspend fun import(uri: Uri, targetPad: DrumPad): ImportResult
    suspend fun resolve(kitId: String): PlayableKit?
    suspend fun delete(kitId: String): DeleteResult
}
```

约束：

- `import` 成功返回时，Room 记录和正式文件必须同时可用。
- `resolve` 只返回已校验、文件存在且完整映射的可播放音色。
- `delete` 不得删除仍被其他音色包引用的音频资产。
- DAO 不直接暴露给界面、ViewModel 或 DrumEngine。
- DrumEngine 只接收 `PlayableKit` 或已解码的样本，不了解 Room。

## 导入流程

### 1. 选择文件

使用 Android Storage Access Framework 的 `ACTION_OPEN_DOCUMENT`。用户通过系统选择器选择文件，应用不申请广泛文件系统权限。

选择器 URI 只作为导入来源。实时演奏不长期依赖该 URI，以避免云端提供者不可用、授权变化或原文件被移动。

### 2. 临时复制

- 将输入流复制到 `filesDir/audio-import-staging/<operation-id>.tmp`。
- 在复制过程中累计大小并计算 SHA-256。
- 超过限制立即终止并清理临时文件。
- 不把整个未知文件一次性读入内存。

### 3. 校验

MVP 推荐只接受明确可控的 WAV 子集，之后再扩展 MP3、M4A 等格式。

最低校验项：

- RIFF/WAVE 和 chunk 结构完整。
- `fmt`、`data` 范围不越界，声明长度不超过真实文件。
- 编码、位深、声道数和采样率在支持范围内。
- 能完整解码，没有截断、NaN 或无限值。
- 时长和文件大小符合限制。
- 解码后至少有一个有效音频帧。

建议初始产品限制：单文件不超过 10 MiB、时长不超过 5 秒；最终数值应在下一任务 PRD 中确认。静音文件可警告但不必默认拒绝。

### 4. 规范化

- 双声道按明确规则转换为单声道。
- 输入音量不在导入时做破坏性自动归一化，除非产品明确提供开关。
- 将输入转换为 DrumEngine 明确支持的标准磁盘格式。
- 标准采样率必须与 Oboe 输出采样率契约一起设计；在该契约确定前，不应仅通过修改 WAV 头假装完成重采样。
- 规范化只发生一次，不得在实时击打路径执行解码或转码。

### 5. 原子提交

推荐顺序：

1. 完成临时文件校验与规范化。
2. 生成不可冲突的 `storageKey`。
3. 将临时文件原子移动到正式目录。
4. 在 Room 事务中写入 AudioAsset、Kit 和映射。
5. 数据库事务失败时删除刚移动的文件；删除失败则由启动协调器清理孤儿文件。

不得先写入 READY 数据库记录，再异步复制文件，否则崩溃后会产生“数据库存在、文件不存在”的假可用状态。

### 6. 预加载与切换

- KitLibrary 将 Room 记录解析成完整 `PlayableKit`。
- 后台线程读取和解码全部样本。
- 所有鼓垫准备完成后，再一次性切换 DrumEngine 使用的样本集合。
- 任一必需样本失败时保持旧音色继续可用，并向界面返回明确错误。
- 不在主线程或原生音频回调线程执行文件 I/O、哈希、解码或 Room 查询。

## 删除与一致性

删除音色包时：

1. 删除 KitPadMapping 和 Kit。
2. 查询每个 AudioAsset 是否仍被其他映射引用。
3. 无引用资产标记为 PENDING_DELETE。
4. 删除文件后删除资产记录；失败时保留状态供下次清理。

应用启动时执行轻量协调：

- 清理过期 staging 文件。
- 将记录存在但正式文件缺失的资产标记为 BROKEN。
- 只删除能够证明无引用的孤儿文件。
- 不在启动主路径执行大规模重新解码或哈希。

## 三类迁移契约

### 1. Room Schema 迁移

- 正式音色库启用 `exportSchema = true`。
- 配置 `room.schemaLocation` 并把 Schema 历史提交到仓库。
- 每个版本提供自动或手写 Migration。
- 使用 `MigrationTestHelper` 从所有仍受支持的旧版本升级并验证数据。
- 导入功能发布后禁止 destructive migration。

当前数据库只包含可重新生成的内置摘要，且没有外部用户数据。建议在导入功能发布前建立新的正式数据库基线，例如 `void_music_library.db`；旧摘要库可以停止使用，不必把无价值结构永久带入正式 Schema。

### 2. 音频资产格式迁移

`storageVersion` 描述磁盘音频格式，`validationVersion` 描述校验规则。两者不得与 Room 数据库版本混用。

- 新版本仍能读取旧资产时，只更新校验版本。
- 必须转码时，在后台创建新文件，验证成功后以事务更新 storageKey 和版本。
- 转换失败保留旧文件与旧记录，不破坏当前可播放状态。
- 不在数据库打开回调或音频线程中执行转码。

### 3. DataStore 设置迁移

- 新增稳定的 `activeKitId`，不再依赖 `activeKitIndex`。
- 首次读取新字段缺失时，将旧 index 映射为对应 BuiltInKit ID。
- 找不到目标音色、音色损坏或已删除时回退到默认内置音色。
- 完成迁移后写入设置版本，避免重复迁移。

## 测试要求

### JVM 测试

- WAV 解析：未知 chunk、奇数 padding、截断数据、伪造长度、无 data、错误格式。
- 单/双声道转换和真实采样率读取。
- 大小、时长和格式限制。
- SHA-256 重复资产识别。
- `activeKitIndex → activeKitId` 迁移与默认回退。
- PlayableKit 完整性与缺失文件处理。

### Room 测试

- 新库创建后的 Schema。
- 受支持版本逐级迁移。
- 外键、联合唯一约束和事务回滚。
- 删除音色时共享资产不被误删。

### 仪器测试

- 从本地、下载目录和云文档提供者导入。
- 导入期间应用被杀死后的 staging/孤儿恢复。
- 文件损坏、空间不足和权限失效。
- 导入后离线演奏。
- 签名 Release 中导入、切换、后台恢复和重新启动。

### 音频验收

- 导入和内置音色的触发延迟没有可感知差异。
- 切换音色时旧音色持续可用，直到新音色完整预加载。
- 快速连续击打和跨颜色切换无新增漏声。
- 不在音频回调路径产生文件 I/O、Room 查询或内存分配。

## 推荐任务拆分

下一项开发建议拆为四个可独立验收的阶段：

1. **音色库模型与迁移基线**：正式 Room Schema、KitLibrary、activeKitId 和迁移测试。
2. **WAV 导入与校验**：SAF、临时复制、严格解析、哈希、原子提交。
3. **播放接入**：PlayableKit、后台预加载、Oboe/SoundPool 文件样本支持和安全切换。
4. **管理与恢复**：重命名、删除、损坏状态、孤儿清理和签名 Release 真机验收。

第一版只支持经过明确校验的 WAV，可以显著缩小错误面。MP3、M4A、裁剪、波形编辑、云同步和音色包导出应留到独立后续任务。

## 参考资料

- [Android：迁移 Room 数据库](https://developer.android.com/training/data-storage/room/migrating-db-versions)
- [Android：Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)
- [Android：应用专属文件](https://developer.android.com/training/data-storage/app-specific)
