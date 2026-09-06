# 实施计划

## 分支与提交策略

- 分支：`codex/audio-import-library`
- 基线：`master@7373bfe`（Void Music 0.1.0-m9）
- 每个阶段独立提交；不得把 Schema、导入器、音频后端和 UI 全部堆入一个提交。
- 每阶段保持内置音色可编译、可播放，可通过隐藏入口回滚未完成的自定义音色功能。

## 阶段 0 — 契约测试与基线

- [x] 固化现有 `BuiltInKits`、Settings JSON、WavDecoder 和 DrumEngine 切换行为测试。
- [x] 增加非法 WAV、损坏音色和切换失败时旧音色保持可用的失败基线。
- [x] 记录当前 Debug 单测、Lint、Room 仪器测试和签名 Release 状态。

质量门：现有行为被测试捕获，新增测试在实现前能暴露目标缺口。

## 阶段 1 — 正式存储与设置迁移（P0）

- [x] 新建 `void_music_library.db`、三张正式表、外键/索引/唯一约束和事务 DAO。
- [x] 启用 `exportSchema=true`、配置 Schema 输出目录并提交 v1 Schema。
- [x] 新建 `AssetStore`，限制相对 storageKey，管理 staging/assets 目录和原子移动。
- [x] 建立最小 `KitLibrary` 只读目录，合并内置音色与 Room 自定义音色。
- [x] 新增 `activeKitId` 与选择迁移版本，兼容读取 `activeKitIndex`。
- [x] 增加 Schema、DAO、设置迁移与路径约束测试（PJZ110 真机完整仪器套件已通过）。

质量门：旧设置准确迁移；新库可创建且约束生效；没有启动写入内置音色。

## 阶段 2 — WAV 校验与规范化（P0）

- [x] 把 WAV 解析、校验和解码合并为单一边界模型。
- [x] 覆盖 RIFF/WAVE、fmt/data、未知 chunk、奇数 padding、边界、帧对齐和 PCM16 单/双声道。
- [x] 流式复制时执行 10 MiB 限制与 SHA-256。
- [x] 实现双声道转单声道、真实重采样和规范化 PCM16 WAV 输出。
- [x] 强制 5 秒时长上限并清理所有失败 staging 文件。
- [x] 增加解析模糊输入、边界和规范化输出回读测试。

质量门：无越界、OOM 或残留临时文件；规范化文件可由同一解析器重新验证。

## 阶段 3 — 原子导入与一致性（P0）

- [x] 实现 `copyKit`、`replacePad`、内容去重和事务提交。
- [x] 增加数据库/移动/删除失败补偿和稳定错误类型。
- [x] 实现共享资产引用检查、PENDING_DELETE 与轻量 `reconcile()`。
- [x] 覆盖中断、重复导入、共享删除、缺失文件和孤儿恢复测试（PJZ110 真机完整仪器套件已通过）。

质量门：成功结果文件与数据库同时可用；所有失败路径保留原映射且可恢复。

## 阶段 4 — 音频准备与无损切换（P0）

- [x] 用明确的内置/导入来源模型替换 `SampleRef` 布尔分支。
- [x] 后台构建 `PreparedKit`，移除 MainScreen/主线程同步文件 I/O。
- [x] DrumEngine 仅在准备成功后切换，并在启动失败时恢复旧音色。
- [x] SoundPool 支持受控文件路径，Oboe 继续消费预解码 PCM。
- [x] 回归连续击打、路由恢复、后台恢复和 STEP Transport。

质量门：损坏音色永不替换正在发声的旧音色；两种后端都能播放导入采样。

阶段 4 验证结果：失败切换/前台恢复/过渡击打/路由错误/100 次密集击打 JVM 回归通过；原有 STEP Transport 回归通过；JNI/Oboe 三 ABI 编译通过；PJZ110 真机仪器测试覆盖导入音色 SoundPool 加载与五鼓垫触发；签名 Release 人工验收确认导入音色实际发声、快速同色连击和跨颜色切换正常。

## 阶段 5 — 设置页管理 UI（P1）

- [x] 增加音色库列表、复制当前音色、重命名和删除入口。
- [x] 每个鼓垫使用 `OpenDocument` 导入 WAV，显示进度与可行动错误。
- [x] UI 只调用 ViewModel/KitLibrary，不读取 DAO、文件路径或 URI 持久状态。
- [x] 删除当前音色时先回退默认音色；操作期间避免重复提交。
- [x] 补齐 Compose/仪器测试和无障碍描述。

质量门：完整用户流程可在离线状态完成，旋转/后台/重启不丢失状态或重复导入。

阶段 5 验证结果：设置与演奏目的地共享 Activity 级 `SessionViewModel`；复制、预准备后选择、重命名、活动音色先回退再删除、五鼓垫 SAF 导入、忙碌锁和可行动错误已接入；PJZ110 上 Compose 与 RoomKitLibrary 定向套件通过。真实文档提供者导入、离线冷启动和后台恢复已在阶段 6 的签名 Release 人工验收中通过。

## 阶段 6 — 完整验证与归档

```bash
./gradlew testDebugUnitTest lintDebug
./gradlew connectedDebugAndroidTest
./gradlew -PenableNativeBuild=true assembleRelease
bash scripts/validate_release_shrinker.sh
```

- [x] 运行所有自动化质量门并验证 Room 导出 Schema 已跟踪。
- [x] 签名 Release 真机导入 5 个采样、切换、快速演奏、后台恢复和离线重启。
- [x] 验证错误文件、超限文件、重复文件、共享资产删除和活动音色删除回退。
- [x] 检查 keystore、外部 URI、绝对路径、staging 和导入音频未被 Git 跟踪。
- [x] 更新项目 spec、用户说明和任务验收记录。

阶段 6 最终结果（2026-09-06）：JVM 单测、Lint、三 ABI Debug/JNI 构建通过；PJZ110 完整仪器套件 24/24 通过；专用签名 Release 与 R8 收缩契约通过。Debug 包通过真实 ColorOS 文件选择器导入、私有目录落盘、冷启动选择保持、活动音色删除回退和零 staging/资产残留验证。签名 Release 人工验收完成 `kick.wav`、`snare.wav`、`clap.wav`、`tom.wav`、`hihat.wav` 五路导入，并确认即时演奏、快速同色连击、跨颜色切换、后台恢复、离线冷启动、异常导入保持原映射及删除回退均正常；SoundPool 后备路径由真机仪器测试覆盖。阶段 6 无遗留验收项。

## 高风险文件与回滚点

- `persistence/KitDatabase.kt`：旧摘要模型被正式库替换；先提交 Schema 与 DAO 测试。
- `persistence/Settings.kt` / `SettingsRepository.kt`：JSON 兼容；迁移失败必须回退内置默认音色。
- `audio/WavDecoder.kt`：不允许用头字段替代边界校验或真实重采样。
- `audio/DrumEngine.kt` / `SoundPoolDrumEngine.kt`：切换失败不能中断旧音色；独立阶段提交。
- `ui/screens/MainScreen.kt`：避免重新把音色库 I/O 编排塞回 Compose 页面。
- `ui/screens/SettingsScreen.kt`：只在底层契约和音频切换稳定后接入管理 UI。

## 当前执行点

阶段 0–6 已全部完成，自动化质量门与签名 Release 真机人工验收均通过。当前任务可进入最终差异复核、提交与归档；实时识别参数未改动。
