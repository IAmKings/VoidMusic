# 运行性能与稳定性基线

日期：2026-09-05

分支：`codex/runtime-performance-stability`

代码基线：`785fbce9`
版本：`0.1.0-m8`（versionCode 3）

## 自动化基线

- `testDebugUnitTest`：通过。
- `lintDebug`：通过；0 errors、80 warnings。
- `assembleDebug`：通过，包含 arm64-v8a、armeabi-v7a、x86_64 原生构建。
- `assembleRelease`：通过，签名输入校验通过。
- Release APK SHA-256：`77c123e09abdd089155df950e6b91c609a439c549038404785295a737d063fd7`。
- 已使用 `adb install -r` 成功覆盖安装签名 Release，保留应用数据。

已知构建环境提示：Android SDK command-line tools 目录存在版本位置不一致警告，但不影响本次构建成功；该环境问题不纳入应用性能任务范围。

## 当前真机

- 设备：PJZ110（USB，设备状态正常）。
- 冷启动 Activity：成功。
- 冷启动 `am start -W` TotalTime：117 ms。本数值只表示 Activity 启动，不代表相机和 MediaPipe 完全可用时间。
- 启动后 TOTAL PSS：76,843 KiB。
- 启动后 TOTAL RSS：244,700 KiB。
- 启动后 Native Heap PSS：17,140 KiB。
- 采集时 Thermal Status：0。
- 采集时电池温度约 35–36.5°C；shell skin 约 36°C。

上述内存和温度只是一次冷启动快照，不能用于证明长期性能收益；后续必须在相同演奏场景下重复采样。

## 可复用识别基线

`research/vision-performance/report-source.md` 在同一 PJZ110 真机、真实彩色物品、手部和击打场景记录：

- 分析/手部结果：15 FPS。
- 色块分割 P50/P95：27/39 ms。
- 鼓区缓存年龄 P50/P95：67/134 ms。
- 相机源帧到 `DrumEngine.trigger()` 返回：175/208 ms。

最后一项不是声学出声延迟，不包含 Oboe 下一次 callback、设备 buffer 和扬声器传播，只能作为旧链路参考。该报告基线为 `master@b148d4e`，优化后对比必须重新采集当前基线与优化结果，不能直接混用提交间数据。

## 待人工完成的阶段 0 记录

- 在当前 `785fbce9` Release 包中记录 TAP 同色连续击打、四颜色切换和双手交替。
- 记录扬声器 → 耳机/蓝牙 → 扬声器的切换结果。
- 记录后台 20 分钟恢复。
- 使用固定机位、光照、彩色物品和节奏重新记录 HUD P50/P95。
- 完成高/中/低档性能、温升、PSS/native heap 对照。

这些人工项目不会阻塞可由自动化保护的阶段 1 实现，但必须在阶段 1 质量门和最终签名 Release 验收前补齐。

## 阶段 1 真机回归记录

- 优化后签名 Release 已通过 `adb install -r` 覆盖安装，冷启动 TotalTime 96 ms。
- 诊断栏确认主后端为 Oboe，采样时 `droppedTriggers=0`、`xRun=0`。
- 首轮后台测试发现 Camera 分析线程与 HandLandmarker.close 并发，触发 MediaPipe JNI SIGSEGV；已将手部模型 setup/detect/close 串行化。
- 修复后完成 20 次前台/后台自动循环，PID 保持 14139，Crash buffer 为空。
- 前台只有 1 个 `VM-Audio-Recovery` 和 1 个 AudioTrack；后台两者均释放，返回前台各恢复 1 个。
- 8 个 `VM-Cam-1-1` 名称来自同一 CameraModule/Executor 创建的 MediaPipe 原生线程；20 次循环前后数量未增长。

仍需人工完成：实际连续演奏听感，以及扬声器、耳机/蓝牙、再返回扬声器的路由切换验证。
