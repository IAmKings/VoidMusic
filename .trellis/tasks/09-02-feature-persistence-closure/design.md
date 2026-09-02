# 核心功能与持久化闭环 — 技术设计

## State Ownership

`SessionViewModel` 持有用户可见设置和会话状态；DataStore 保存小型 JSON 快照（模式、检测配置、序列、校准和偏好），Room 保存 Kit 元数据。迁移使用显式 schema/version，解析失败回退默认并保留可诊断日志。

## Picker and Kit Flow

取色从最近可用的分析帧读取 HSV，生成经边界约束的 HsvRange，更新当前 preset。Kit catalog 至少提供两套真实不同的 pad 映射；UI 选择 → 持久化 activeKit → `DrumEngine.setKit`，并以成功结果更新 UI，避免“已选但未生效”。

## Settings Flow

所有检测参数具备单一来源、值域校验与实时观察流。重置先显示确认对话框，再以单个事务恢复 DataStore/Room 默认值；恢复后重新应用给相机、检测和音频。

## Compatibility

既有单 JSON settings 需平滑读取；缺失的新字段使用默认值。校准和序列使用可序列化 DTO，避免直接持久化 UI/runtime 对象。
