# PRD 验收闭环 — 技术设计

## Delivery Model

父任务不直接修改运行时代码；它协调四个子任务并持有最终验收证据。

```
native-audio-build ───────┐
                            ├─> feature-persistence-closure ─> release-performance-validation
vision-hit-correctness ───┘
```

`native-audio-build` 与 `vision-hit-correctness` 可以并行。功能闭环可提前完成其无依赖部分，但 Kit 接入必须消费已稳定的音频 API。发布验收最后执行。

## Cross-Task Contracts

1. 音频任务提供线程安全、幂等的 `start`、`trigger`、`setKit`、`stop` 契约，并暴露当前后端与可测量的延迟诊断。
2. 视觉任务提供同一 preview 坐标系中的不可变 `Hand`、`DrumZone`、`GridPoint` 事件；分析线程不得写 Compose state 或调用 `View`。
3. 功能任务只通过上述公开契约编排状态；DataStore/Room 的数据模型有迁移策略。
4. 验收任务只消费构建产物、诊断指标和设备记录，不以日志或代码注释替代测量。

## Release Decision

正式发布候选仅在以下全部满足时产生：release 包 <80MB、三档实体设备均通过、隐私检查通过、根 PRD F1–F8 的证据表更新完成。任何一项未完成，只能产出内部测试版。

## Rollback

每个子任务独立提交并维持可构建状态；若新 Oboe 路径不稳定，保留 SoundPool 回退用于内部验证，但不得据此宣称低延迟指标达标。
