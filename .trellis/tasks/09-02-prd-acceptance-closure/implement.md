# PRD 验收闭环 — 执行计划

1. 清理或释放开发机磁盘空间，确保 release 构建和 Profiler 工件有充足空间。
2. 完成并验证 `09-02-native-audio-build` 与 `09-02-vision-hit-correctness`。
3. 完成功能与持久化闭环；与已稳定的音频、视觉契约集成。
4. 构建 release，执行三档真机性能矩阵并收集证据。
5. 对照根 `prd.md` §12 更新功能、性能和隐私验收表；仅在全部通过后归档父任务。

## Integration Gates

- 每个子任务：lint、单测、Debug 构建通过；新增行为拥有回归测试。
- 音频：所有目标 ABI 的原生构建通过。
- 视觉：竖屏/横屏真机叠加与命中验证通过。
- 发布：三档设备记录完整，release APK 大小已实测。

## Evidence Location

性能矩阵、截图、测量方法和构建版本写入 `09-02-release-performance-validation/` 的任务文档；父任务只引用最终结论。
