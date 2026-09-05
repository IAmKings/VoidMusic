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

正式发布候选版阶段仍需人工完成：标准化前台连续演奏 20 分钟，以及独立高/中/低三档设备矩阵。

## 阶段 2 真机回归记录

- 深层 Pipeline、绝对截止时间 Transport 和 runtime constraints 合入签名 Release 后，冷启动 TotalTime 107 ms。
- 前台恢复后分析 FPS 为 15，音频保持 Oboe，`droppedTriggers=0`、`xRun=0`。
- 完成 5 次前台/后台循环，PID 保持 26288，Crash buffer 为空；恢复后 FPS 回到 15。
- ColorSegmenter 改为每次 Pipeline start 新建、stop 释放，消除了关闭后复用已释放 OpenCV Mat 的隐患。

## 阶段 3 热路径优化记录

### 资源所有权结论

- CameraX `ImageProxy.toBitmap()` 产生的原始 Bitmap 和旋转后的共享 Bitmap 均由 FrameRouter 所有。
- MediaPipe 0.10.35 的 `BaseVisionTaskApi.sendLiveStreamData()` 在 `detectAsync()` 返回前同步调用 `AndroidPacketCreator.createImage(MPImage)`，因此原生输入包已在消费者返回前创建。
- `BitmapImageBuilder` 只包装传入 Bitmap；关闭该输入 MPImage 会回收共享 Bitmap，因此不能在 HandTracker 内关闭输入包装器。FrameRouter 在手部与色块消费者都返回后统一回收共享 Bitmap。
- MediaPipe 结果监听器收到的是从输出 image packet 新建的 Bitmap/MPImage，不是共享输入；该对象现已在回调 `finally` 中关闭。
- 旧色块路径每次降采样都会创建缩放 Bitmap，每个 preset 都会创建 hierarchy Mat 与轮廓 List；现改为复用缩放 Mat、hierarchy、轮廓容器和既有阈值 Mat。

### 自动化与真机结果

- FrameRouter JVM 测试覆盖单个/全部 consumer 异常、后续 consumer 继续执行和 frame 必然只关闭一次。
- SegmentationCadence JVM 测试覆盖首次立即分割、15/30 FPS 稳态频率、配置失效立即刷新和场景变化恢复活跃频率。
- PJZ110 真机 OpenCV 合成图测试通过：0.5 倍降采样、连续 3 轮工作区复用时均能识别红/蓝色块。
- 最终签名 Release 冷启动 `am start -W` TotalTime 97 ms，APK SHA-256 为 `f22f0fc38667f98d0a26d6ffbe62cb435af41487b9616255fa55800752039eec`。
- 无物体/无手的稳定空闲画面，HUD 最终稳定在分析/手部结果 15 FPS（短暂观察到 21 FPS）；采集→手部 P50/P95 为 136/156 ms，回调→消费为 0/0 ms，分割为 1/2 ms。空轮廓场景不可与旧真实物件 27/39 ms 分割基线直接对比。
- 5 次前台/后台恢复后 PID 为 21281，进程存活，Crash buffer 与 FrameRouter/HandTracker 错误日志为空。
- 首个活跃快照：TOTAL PSS 277,115 KiB、Native Heap PSS 67,582 KiB、Bitmap 15 个/27,622 KiB。继续运行并经过 GC 后复采为 TOTAL PSS 188,175 KiB、Native Heap PSS 80,619 KiB、Bitmap 6 个/177 KiB；Bitmap 数量与占用下降而非持续增长，说明逐帧共享 Bitmap 已可回收。两次采集时相机、OpenCV、MediaPipe 与 GPU 均已活跃，仍不能与仅 Activity 冷启动的旧内存快照直接比较。
- 本轮 connected test 首次因设备已有 Release 签名与 debug test 包不一致而失败；测试框架卸载了旧包，可能清除本地应用数据。随后测试通过，并已重新安装签名 Release。
- 提交前组合执行测试、Lint 和 Release 时，Android Lint 对 instrumented test 发生一次 Kotlin FIR 内部分析异常；相同文件此前已通过，拆分后使用 `lintDebug --rerun-tasks` 完整通过，确认是工具链并行/缓存偶发故障而非代码告警。

仍需在固定彩色物品、光照和实际击打场景下采集三档分割 P50/P95、缓存年龄、击打提交延迟、温升和 20 分钟结果；当前空闲数据不替代完整设备矩阵。

### PJZ110 真实物件与手部击打复测

用户在最终签名 Release 中放入彩色物品并连续击打约 20 秒，采集时 HUD 同时识别到 7 个色块区域和 1 只手：

| 指标 | P50 | P95 | 结论 |
|---|---:|---:|---|
| 分析 / 手部结果 FPS | 15 FPS | — | Thermal Status 2 触发运行时低档，符合 15 FPS 档位 |
| 采集 → 手部回调 | 138 ms | 163 ms | 低于 260 ms 手部硬上限 |
| 回调 → 消费 | 0 ms | 0 ms | TAP 快速路径没有排队 |
| 色块分割 | 2 ms | 3 ms | 远低于 25 ms 单次预算 |
| 鼓区缓存年龄 | 66 ms | 134 ms | 远低于 260 ms 击打缓存上限 |
| 候选 → 音频提交 | 0 ms | 0 ms | 候选确认后同步提交，无额外队列延迟 |

- 音频后端保持 Oboe，`droppedTriggers=0`、`xRun=0`。
- 旧同机真实物件记录为分割 27/39 ms、缓存 67/134 ms、分析/手部 15 FPS；本轮分割降至 2/3 ms，缓存新鲜度没有退化。由于物件摆位和光照没有严格冻结，该差异作为强趋势证据，不宣称为严格实验室增益比例。
- 本轮指标仍是“候选到引擎提交”而非麦克风声学延迟；不能替代真正的击打到扬声器出声测量。
- 演奏后内存快照为 TOTAL PSS 182,085 KiB、Native Heap PSS 71,430 KiB、Bitmap 52 个/37,110 KiB。停止击打并继续前台运行约 30 秒后，复采为 TOTAL PSS 185,506 KiB、Native Heap PSS 74,154 KiB、Bitmap 14 个/2,910 KiB；Bitmap 峰值能够被回收，没有随帧数持续增长。
- 演奏后电池温度 40.4°C、`shell_skin` 41.0°C、Thermal Status 2。该设备在此前连续构建和测试后已升温，因此不能据此计算标准 20 分钟温升，只能确认运行时热约束已生效。
- 结束时 PID 仍为 31614，Crash buffer 为空。

PJZ110 单机真实演奏热路径已通过。最终设备矩阵仍缺少独立中/高档设备和标准化 20 分钟温升、声学延迟数据。

### PJZ110 后台 20 分钟恢复

在真实物件/手部击打采集完成后，将最终签名 Release 送入后台 1,210 秒（20 分 10 秒），期间保持 USB 连接并持续采样：

| 时间 | PID | TOTAL PSS | Bitmap | 电池温度 |
|---:|---:|---:|---:|---:|
| 0 秒 | 31614 | 73,277 KiB | 11 个 / 210 KiB | 39.7°C |
| 550 秒 | 31614 | 67,255 KiB | 11 个 / 210 KiB | 35.3°C |
| 1,100 秒 | 31614 | 66,676 KiB | 11 个 / 210 KiB | 34.4°C |
| 1,210 秒 | 31614 | 66,679 KiB | 11 个 / 210 KiB | 34.0°C |

- 20 分钟内 PID 始终为 31614，未发生进程重启；后台 PSS 下降后稳定，Bitmap 数量和占用完全不增长。
- 恢复为 HOT start，TotalTime 219 ms；恢复后 PID 仍为 31614。
- Thermal Status 从演奏后的 2 恢复为 0，应用有效性能从 15 FPS 自动回升，恢复后分析/手部结果约 20 FPS。
- Oboe 自动恢复，`droppedTriggers=0`、`xRun=0`；恢复初期采集→手部 P50/P95 为 174/195 ms，仍在 260 ms 硬上限内。
- 重新放入物件和手后，HUD 识别到 7–8 个色块区域和 1 只手，确认相机、OpenCV 和 MediaPipe 均无需重启即可恢复。
- 全流程结束后 PID 仍为 31614，Crash buffer 为空。

该项满足单机后台 20 分钟恢复验收。标准化前台连续演奏 20 分钟和独立高/中/低三档设备矩阵延后到正式发布候选版执行。

### PJZ110 扬声器 → 蓝牙耳机切换

- 连接 OnePlus Buds 4 后，系统媒体路由确认为 Bluetooth A2DP，AudioFlinger 中 Void Music（PID 31614）的活动音轨输出设备为 `AUDIO_DEVICE_OUT_BLUETOOTH_A2DP`。
- 切换前后 PID 均为 31614，应用无需重启；切回前台为 HOT restore，耗时 213 ms。
- HUD 显示音频后端保持 Oboe，分析/手部约 20/21 FPS，`droppedTriggers=0`、`xRun=0`，采集→手部 P50/P95 为 158/180 ms。
- 人工连续击打并切换不同颜色，声音正常从蓝牙耳机播放，延时符合预期，跨颜色切换流畅。

断开蓝牙后，系统媒体路由自动返回 `AUDIO_DEVICE_OUT_SPEAKER`；应用 PID 仍为 31614，音频后端继续保持 Oboe。返回扬声器后的 HUD 为分析/手部 20/20 FPS、`droppedTriggers=0`、`xRun=1`，采集→手部 P50/P95 为 151/172 ms。该 1 次欠载发生在物理路由切换窗口内，没有造成击打触发丢失。人工复测确认外放声音正确、响应迅速，跨颜色切换流畅。

完整链路“扬声器 → 蓝牙耳机 → 扬声器”通过：两次切换均无需重启，系统路由与活动音轨输出一致，Oboe 自动维持或恢复，实际声音输出正确且无可感知漏声。音频路由验收项关闭。

### PJZ110 前台 5 分钟连续点击冒烟测试

用户在当前签名 Release 上完成约 5 分钟前台连续点击，期间未发现无声、明显延迟、跨颜色切换卡顿、界面冻结或崩溃。该结果作为当前开发阶段的快速稳定性信号，不替代正式发布候选版的标准化前台连续演奏 20 分钟、温升及设备矩阵验收。

### 正式发布前延期项

当前暂无独立高/中/低三档真机。三档设备矩阵、每档前台连续演奏 20 分钟、标准化温升和声学延迟统一纳入 [`release-performance-validation.md`](../release-performance-validation.md)，在最终签名正式发布候选版上执行。本阶段已有 PJZ110 结果保留为开发期证据，但不冒充完整设备矩阵。
