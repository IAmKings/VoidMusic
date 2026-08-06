# M5: 设置 / 持久化 / 样本库

## Goal

实现 DataStore + Room 持久化存储、设置界面、内置音色组切换、参数在重启后恢复。覆盖 PRD F7（P1）和 F8（P0）。

工期 1.5 周。

## Requirements

### R5.1 参数持久化（PRD F8.1）

- `DetectionConfig`（HSV 预设、灵敏度、防抖、平滑系数、性能档位）使用 DataStore (Preferences) 持久化。
- 复杂字段（`HsvPreset` 列表、`SequenceState`）以 JSON 序列化存入 DataStore 键值。
- App 重启后自动恢复上次设置。
- 设置变更时通过 `Flow` 通知检测层实时更新。

### R5.2 设置界面（PRD F8.3）

Compose 设置页 `SettingsScreen`，包含：

| 设置项 | 类型 | 说明 |
|--------|------|------|
| 性能档位 | 单选（高/中/低） | 影响推理分辨率与帧率上限 |
| 振动开关 | 切换 | 击打命中触觉反馈 |
| 灵敏度 | 滑块 | 击打检测灵敏度 |
| 防抖时间 | 数值输入 | 连续击打最小间隔 |
| 平滑系数 | 滑块 | 手部轨迹平滑度 |
| 默认音色组 | 下拉 | 内置 Kit 选择 |

### R5.3 重置默认（PRD F8.2）

- 一键恢复所有设置为工程默认值。
- 弹出确认对话框后执行。

### R5.4 内置样本库与 Kit 切换（PRD F7.1、F6.6）

- 内置 ≥ 2 套鼓组音色（如 Electronica Kit、Acoustic Kit）。
- 每套 Kit 包含 Kick/Snare/Clap/Tom/Hi-Hat 的样本或合成参数。
- Kit 切换即时生效。
- `Kit` 数据模型使用 Room 存储（支持后续扩展导入样本）。

### R5.5 持久化架构

| 数据类型 | 存储 | 键/表 |
|---------|------|-------|
| `DetectionConfig` | DataStore | `detection_config` (JSON) |
| `HsvPreset` 列表 | DataStore | `hsv_presets` (JSON) |
| `SequenceState` | DataStore | `sequence_state` (JSON) |
| `Kit` / `SampleRef` | Room | `kits` 表 + `samples` 表 |

## Acceptance Criteria

- [ ] 调整 HSV 阈值后 App 重启，阈值恢复为上次设置的值
- [ ] 切换模式（击打/步进）后重启，恢复为上次使用的模式
- [ ] 设置页所有控件可交互，修改即时生效
- [ ] 重置默认：点击后所有参数恢复为工程默认值
- [ ] 切换音色组后鼓声音色可感知变化
- [ ] DataStore 写入无 ANR（异步操作，主线程安全）
- [ ] Room 数据库表结构正确，Kit 数据可增删查改

## Out of Scope

- 自定义样本导入（P2，后续迭代）
- 样本映射到指定鼓区/步进行（P2）
- 云端备份/恢复
- 预设管理（保存多套校准/HSV 方案，P2）

## Dependencies

- 依赖 M3：DetectionConfig 中的灵敏度/防抖参数需在 M3 中已定义
- 依赖 M4：SequenceState 模型需在 M4 中已定义
- 推荐在 M3/M4 功能稳定后再做持久化层
