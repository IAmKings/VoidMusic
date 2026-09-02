# 原生音频构建与兼容回退

## Goal

交付可编译、线程安全的 Oboe 低延迟主音频路径，并保留可用的 SoundPool 兼容回退，解除 PRD F6 与 M6 的发布阻塞。

## Confirmed Facts

- `enableNativeBuild` 默认关闭；默认 APK 不含 `libdrumengine.so`。
- 启用后所有 ABI 均因 `AudioStream.getLatency()` 不存在及复制含 `std::atomic` 的 `Voice` 而失败。
- 当前回调与 JNI 触发并发读写 voice 字段，没有明确的实时线程同步契约。

## Requirements

- Oboe CMake 构建成为 Debug/Release 的受检路径，目标 ABI 均能编译。
- 音频回调不得分配、锁定或访问有数据竞争的共享 voice 状态。
- JNI 触发以有界、无阻塞的命令机制交给音频线程；满载时采用明确的丢弃/抢占策略。
- 使用 Oboe 1.10 支持的时间戳/延迟诊断 API；真实设备测量仍是 <40ms 的唯一验收依据。
- SoundPool 回退在原生库不可用时可出声、支持五个 pad 与并发播放；其更高延迟不得冒充 Oboe 达标。
- 为 Kit 任务提供稳定的 Kotlin 音频 API，支持原子切换整套样本。

## Acceptance Criteria

- [x] `-PenableNativeBuild=true` 下 Debug 与 Release 的目标 ABI 均编译通过。
- [ ] Oboe 主路径在支持设备上实际加载、播放五种 pad，并支持并发触发。
- [ ] 并发压力测试无崩溃、杂音或线程检查问题。
- [ ] 禁用原生库时 SoundPool 回退可播放，且状态可观测。
- [ ] 新增原生构建门禁与 Kotlin 层回归测试。

## Out of Scope

- AudioTrack 回退、网络音频、自定义样本导入与 P2 音色编辑。

## Dependencies

- 先释放开发机磁盘空间；后续被 `feature-persistence-closure` 和 `release-performance-validation` 依赖。

## Implementation Progress

- 2026-09-02：将 Oboe 原生构建设为默认路径；Debug 已验证 arm64-v8a、armeabi-v7a、x86_64，Release 已验证 arm64-v8a。
- 2026-09-02：替换不可复制的 atomic voice vector，使用固定 voice pool 与有界无锁 trigger queue，移除不兼容的 Oboe latency API。
- 2026-09-02：release APK 已生成，45MB，包含 `lib/arm64-v8a/libdrumengine.so`。
- 尚需：实体设备的 Oboe 出声/延迟、并发压力及 SoundPool 回退验证；CI 原生构建门禁与 Kit 的原子切换 API。
