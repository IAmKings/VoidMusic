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
