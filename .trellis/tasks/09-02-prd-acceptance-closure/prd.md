# PRD 验收闭环与发布阻塞修复

## Goal

将 Object Drum Studio 从“代码骨架已具备但无法按 PRD 验收”推进到可发布候选状态：关键演奏链路可用、关键功能缺口补齐，并以可复现的构建和真机测量证明根 PRD §12 的验收结论。

## Confirmed Facts

- 默认 Debug 构建与 JVM 单测可通过，但原生 Oboe 路径默认关闭。
- 启用 `enableNativeBuild=true` 后，`drumengine.cpp` 无法编译：使用不存在的 `AudioStream.getLatency()`，且 `std::vector<Voice>::assign` 试图复制含 `std::atomic` 的 `Voice`。
- 手部/鼓区坐标仅处理了图像旋转，未处理 `PreviewView` 的裁剪和缩放；相机分析线程还直接更新 Compose 状态与触觉反馈。
- HSV 分割、击打、4×16 序列器、DataStore 设置均已有部分实现；取色器、跨帧稳定鼓区 ID、真实 velocity、模式/序列/校准持久化、多 Kit 实际切换尚未闭环。
- 性能档位的 `analysisFrameCap` 未接入帧路由；M6 所需三档真机、20 分钟稳定性、延迟、温升、内存和发布包体积均无验收记录。
- 根 PRD 仍要求：API 26+ 本地优先、无 INTERNET 权限、击打到出声 <100ms、Oboe 流 <40ms、发布 APK <80MB。

## Deliverables

本父任务只负责范围、依赖和最终集成验收；实施拆为以下可独立验证的子任务：

1. `09-02-native-audio-build/`：原生音频构建与线程安全，恢复可交付的 Oboe 主路径并保留兼容回退。
2. `09-02-vision-hit-correctness/`：相机坐标、分析线程与击打链路正确性，确保手指、鼓区、网格与触觉事件一致。
3. `09-02-feature-persistence-closure/`：PRD P0/P1 功能闭环：取色、velocity、稳定鼓区 ID、Kit、设置和持久化。
4. `09-02-release-performance-validation/`：发布性能与真机验收：构建体积、指标采集、三档设备矩阵及隐私复核。

## Constraints

- 不降低根 PRD 的核心验收阈值，也不以“代码存在”代替设备验收。
- 保持纯本地处理和最小权限；不得新增 INTERNET 权限。
- 子任务按依赖推进：音频与坐标正确性优先；功能闭环随后；性能验收最后。
- 先释放本机磁盘空间，再执行 release 构建、原生构建与性能测量。

## Acceptance Criteria

- [ ] 四个子任务均拥有可测试的 `prd.md`；复杂子任务拥有 `design.md` 与 `implement.md`。
- [ ] 原生音频 Debug/Release 构建成功；每个 ABI 的 Oboe 路径有自动化构建门禁。
- [ ] 真机上手部、鼓区和网格位置在竖屏/横屏均对齐；击打和步进编辑稳定工作。
- [ ] 根 PRD F1–F8 的实现与验收证据逐项更新，不再使用未验证的“已对齐”标记。
- [ ] 三档真机完成性能矩阵；记录 FPS、端到端延迟、音频流延迟、内存、20 分钟稳定性与温升。
- [ ] 发布包构建成功、大小 <80MB，且隐私清单全部通过。

## Out of Scope

- P2 自定义样本导入、节拍器、预设管理与云端能力。
- 与本轮验收无关的 UI 视觉重做。

## Decisions

- 采用 **Oboe 原生主路径 + SoundPool 兼容回退**。Oboe 负责满足低延迟目标；SoundPool 仅保证原生库不可用或兼容性受限时仍可出声。AudioTrack 不纳入本轮范围。
- 首个发布候选纳入根 PRD 的核心 P0 和 F7/F8 的 P1 闭环：至少两套有可感知差异的 Kit，完整设置项，以及模式、序列、校准和检测参数的重启恢复。
- 三档实体设备（高、中、低）均通过根 PRD §12.3 性能矩阵，是正式发布候选的硬门槛。覆盖不足时只能标记为内部测试版，不得宣称 PRD 验收完成。
