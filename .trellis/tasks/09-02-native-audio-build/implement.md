# 原生音频构建与兼容回退 — 执行计划

1. 释放磁盘空间；用 `-PenableNativeBuild=true` 复现并记录所有 ABI 编译错误。
2. 修复 Oboe API 调用和不可复制 voice storage；为 native 实时线程设计 command queue。
3. 审查 callback/JNI 共享状态，消除数据竞争和 callback 内分配/锁定。
4. 完善 DrumEngine 后端状态、SoundPool ready 时机和 Kit 切换契约。
5. 新增 Kotlin 层测试、各 ABI 原生构建门禁，并在实体设备验证出声和诊断。

## Validation

- `./gradlew -PenableNativeBuild=true :app:externalNativeBuildDebug`
- `./gradlew -PenableNativeBuild=true :app:assembleDebug :app:assembleRelease`
- `./gradlew :app:testDebugUnitTest :app:lintDebug`

## Rollback

每一步先保持 SoundPool 回退可用；若 native 路径回归，只回退该子任务提交，不删除兼容后端。

## Verification Record

- 2026-09-02：`./gradlew -PenableNativeBuild=true :app:externalNativeBuildDebug` 通过。
- 2026-09-02：默认 `./gradlew :app:assembleDebug` 通过，确认所有 Debug ABI 构建原生库。
- 2026-09-02：默认 `./gradlew :app:assembleRelease` 通过；release unsigned APK 为 45MB，且包含 arm64 `libdrumengine.so`。
- 2026-09-02：`./gradlew :app:testDebugUnitTest --rerun-tasks` 与 `./gradlew :app:lintDebug` 通过。
