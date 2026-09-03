# 发布性能与真机验收 — 执行计划

1. 释放磁盘空间，构建 release 并记录 APK 大小与 hash。
2. 审计 Manifest、权限、帧写盘和上传路径，验证隐私说明。
3. 在每档设备运行冷启动、FPS/延迟、内存和后台恢复测量。
4. 每台设备完成 20 分钟持续演奏，记录温升、崩溃、卡顿和音频异常。
5. 将数据写入任务记录，映射到根 PRD §12；全部通过后才批准发布候选。

## Validation

- `./gradlew -PenableNativeBuild=true :app:assembleRelease`
- release APK 大小测量及设备安装验证。
- Android Profiler、高速摄像/波形与设备温度记录。

## 2026-09-02 执行记录

### 已通过：构建与包内容

- 命令：`./gradlew -PenableNativeBuild=true :app:assembleRelease :app:assembleDebug --no-daemon`
- 结果：成功；release APK 为 `app/build/outputs/apk/release/app-release-unsigned.apk`，46,793,711 bytes（约 44.6 MiB），低于 80 MB 门槛。
- SHA-256：`3d21b1d7d1aab26ddc81372e69c7a6d22edfe511030f1dc1bcf2dd310c61314a`。
- 原生包内容：`arm64-v8a/libdrumengine.so` 与 `arm64-v8a/libc++_shared.so` 均存在。
- 基线：`1572d4d`，工作区含未提交变更；该 APK 未签名，不能作为可安装的正式发布候选。

### 已通过：隐私与权限

- 源码审计未发现网络客户端、WebView、Socket、相机帧写盘或上传调用；用户可见文案为“本地优先 · 无网络上传”。
- 初次合并清单发现 MediaPipe 的传递依赖 `transport-backend-cct` 带入了 `INTERNET` 和 `ACCESS_NETWORK_STATE`，与 PRD 冲突。
- 已在应用 Manifest 用 `tools:node="remove"` 明确移除上述两项；重新构建后的 release 打包清单和设备安装包均只声明 `CAMERA`（另有 AndroidX 内部签名权限），因此已复核通过。

### 单机短时证据（不可替代完整矩阵）

- 设备：PJZ110 / Snapdragon 8 Elite（SM8750）/ arm64-v8a / Android 16（API 36）/ 约 22.5 GiB RAM。
- debug 冷启动：501 ms（`am start -W`），低于 3 秒门槛。
- 首屏内存：TOTAL PSS 154,400 KB，低于 400 MB 门槛；该值仅代表引导页，不能替代持续演奏峰值。
- 热状态：系统 Thermal Status 0；读取时 skin 约 39.6°C。未做基线到结束的温升比较，不能判定温升门槛。
- 自动授予摄像头权限被该设备的 shell 策略拒绝；因此本轮没有把“当前重装包”推进到相机主界面进行自动 FPS/音频测量。

### 发布结论：仅内部测试版

以下强制数据仍缺失，正式发布保持阻断：高/中/低三档实体设备的预览与分析 FPS、击打到出声延迟、Oboe 流延迟、实际演奏峰值内存、后台释放/恢复、每台 20 分钟持续演奏、温升和崩溃记录。release APK 还需要签名后在相同协议下重新测量。

## Rollback

不满足门槛时不发布；将失败指标路由回对应子任务，修复后从完整协议重新测量。

## 应用身份迁移（已完成代码与构建验证）

- 已将 Gradle namespace/applicationId、Kotlin 主代码/单测/仪器测试、R8 规则与 JNI 导出符号迁移至 `com.electrodig.voidmusic`，并同步迁移源码目录。
- `DrumEngine` 的四个动态 JNI 导出均为 `Java_com_electrodig_voidmusic_audio_DrumEngine_*`；源码与当前产品文档无旧命名空间残留。
- `./gradlew -PenableNativeBuild=true :app:testDebugUnitTest :app:lintDebug --no-daemon --max-workers=1` 已通过。
- 真机已验证新的主应用可安装、冷启动（519 ms）并仅声明 `CAMERA` 权限；未发现网络权限。
- 仪器测试 APK 在 PJZ110 安装时被设备以厂商错误码 `-99` 拒绝；清理可能残留的测试包后，流式与非流式安装重试均失败（设备有约 537 GiB 可用空间，主 APK 安装成功）。测试尚未执行；需解除该设备对测试 APK 的安装限制后重跑。
- 先前 APK hash、冷启动和内存记录只适用于旧应用身份；正式发布前必须重新采集。

## GitHub Actions 自动构建（2026-09-02）

- 新增 `.github/workflows/android.yml`：向 `master` 推送或发起 PR 时，云端安装 Android API 35、NDK 27.2.12479018 与 CMake 3.22.1，执行原生构建路径下的单元测试、Lint 和 debug APK 编译。
- 推送 `v*` 标签或通过 `workflow_dispatch` 手动触发时，在校验成功后额外构建 `app-release-unsigned.apk`，作为 GitHub Actions 构件保留 90 天。
- 工作流以 `-Dorg.gradle.java.home="$JAVA_HOME"` 覆盖本机 Android Studio JBR 路径，确保 Ubuntu Runner 使用 Temurin JDK 17。
- 当前仓库未配置签名证书或 GitHub Release 凭据，因此发布产物明确为未签名 APK；签名与正式 GitHub Release 将在密钥配置后接入。

## 0.1.0-m7 高端机预检（2026-09-02）

- 设备：PJZ110 / Snapdragon 8 Elite（SM8750）/ arm64-v8a / Android 16（API 36）；安装的测试包为 `versionCode=2`、`versionName=0.1.0-m7` debug APK，`CAMERA` 权限已授予。
- 冷启动两次分别为 547 ms、464 ms；后台返回前台为 HOT 启动 48 ms。后台时顶层 Activity 为系统桌面，恢复后应用正常回到前台，摄像头权限保持；读取最近 1,000 行日志未见该应用的 `FATAL EXCEPTION`。
- 恢复后的进程内存：TOTAL PSS 315,002 KB，低于 400 MB 门槛。采样前设备电池 35.1°C、系统热状态 0；设备当时经 USB 供电，因此该温度只能作为预检背景，不能作为温升结论。
- 曾读取一次 `gfxinfo`，但采样期间 Activity 已进入后台，故该帧统计不计入 FPS 结果。当前仅可判定“安装、启动、短时后台恢复”预检通过；真实演奏 FPS、击打/出声延迟、20 分钟稳定性、温升和中低端档仍未完成。

## 三档设备正式复测协议

### 固定条件

1. 用同一已签名 release APK、同一 commit SHA、同一声音套件和同一性能档位测量；测试前记录 APK SHA-256。
2. 每台设备脱离充电、室温 22–26°C、屏幕亮度固定 60%、关闭省电模式；静置 5 分钟后记录电池温度、热状态和可用存储。
3. 使用同一 4 区鼓面、固定相机支架和光照（约 500 lux）；每次测量前确认相机权限、手部/色块识别和四区命中均可用。

### 每台设备的必做步骤

| 测项 | 操作与记录 | 通过标准 |
| --- | --- | --- |
| 实际演奏 FPS | 连续演奏 2 分钟；同时录制屏幕或以 Android Profiler/`gfxinfo` 采样。仅当前台且相机、手部、色块处理均在运行时取数，记录中位数与 P95。 | 高端 ≥60 FPS；中端 ≥30 FPS；低端降级后可用。 |
| 击打到出声延迟 | 用 ≥240 fps 高速相机同时拍摄鼓面接触与手机扬声器波形/外接录音界面；每区各 10 次，共 ≥40 次，统计中位数与 P95。 | 高端 <80 ms；中端 <100 ms；低端 <150 ms。 |
| Oboe 音频流 | 在同一演奏期间读取应用/系统诊断的流延迟；若无可读数，以声学测量为准，并注明方法。 | <40 ms。 |
| 20 分钟稳定性 | 以 120 BPM 循环四区击打与序列器切换，持续 20 分钟；每 5 分钟记录 FPS、PSS、热状态、温度、卡顿与音频异常。 | 无崩溃、无明显卡顿或断音。 |
| 温升 | 记录静置基线、5/10/15/20 分钟与结束后 2 分钟的电池温度；附环境温度。 | 相对基线 ≤12°C。 |
| 后台恢复 | 演奏 5 分钟后按 Home 30 秒，再回到应用并继续演奏 2 分钟；确认相机/手部追踪/音频重新工作、权限仍在、无崩溃。 | 资源释放且可恢复，无功能异常。 |

### 设备矩阵与状态

| 档位 | 目标设备 | 当前状态 | 下一步 |
| --- | --- | --- | --- |
| 高端 | PJZ110 / Snapdragon 8 Elite / Android 16 | 已完成 m7 安装、冷启动和后台恢复预检；正式演奏数据待采。 | 按上表完成 20 分钟协议与声学录制。 |
| 中端 | 骁龙 7/6 或天玑 7000 级真机 | 未接入。 | 接入后安装同一 signed APK，完整执行上表。 |
| 低端 | 4 GB RAM 入门 Android 8.0+ 真机 | 未接入。 | 接入后先验证降级档，再完整执行上表。 |

## 签名 release 包复测计划

1. 由发布负责人在本机安全位置或 GitHub Secrets 提供 release keystore、keystore 密码、key alias 与 key 密码；密钥文件和属性文件不得提交到仓库。
2. 在 Gradle 中仅通过未跟踪的 `keystore.properties` 或 CI Secrets 注入签名配置，构建 `app-release.apk`；CI 不再上传 `app-release-unsigned.apk` 作为发布候选。
3. 用 `apksigner verify --verbose --print-certs` 验证 APK 签名与证书指纹，记录 APK SHA-256、`versionCode=2`、`versionName=0.1.0-m7` 和构建 commit。
4. 对三档设备卸载旧测试包或确认覆盖安装路径后，安装同一 signed APK；从“固定条件”重新执行所有测项。debug 或未签名 APK 的数据不得混入正式结果。
5. 三档全部满足阈值且签名校验通过后，才允许将该 APK 附加到 GitHub Release；任一缺项或失败均维持内部测试版状态。
