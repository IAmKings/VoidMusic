# 签名 Release 与自动发布闭环 — 执行计划

1. 固化 `0.1.0-m8 / versionCode 3`，补充版本读取与标签一致性检查。
2. 在仓库外生成首套 Void Music 专用 Release keystore，限制文件权限，记录 alias/证书指纹，并要求完成离线备份；密码不得输出到日志。
3. 在 `.gitignore` 增加 `keystore.properties`；在 `app/build.gradle.kts` 实现本地属性/环境变量双入口和 Release 专用完整性校验。
4. 使用正式专用 keystore 在本地验证签名 Release 构建、APK 大小、`apksigner` 结果和证书指纹；构建产物不得携带密码文件。
5. 修改 `.github/workflows/android.yml`：从四项 Secrets 恢复 keystore，构建签名 APK，校验版本/标签、签名和 SHA-256，上传命名稳定的构件。
6. 仅在 `v*` 标签事件全自动创建普通公开 GitHub Release；不使用 Pre-release/Draft，`workflow_dispatch` 只生成 Actions Artifact。
7. 发布说明明确标注 `0.1.0-m8` 为内测里程碑；当前不接入三档设备硬门禁，但为稳定期门禁保留独立步骤位置。
8. 更新 README/发布说明，记录 Secrets 配置、`v<versionName>` 标签规则、证书备份与轮换风险。
9. 运行 Debug 全量门禁；分别验证缺少签名输入的明确失败路径和正式 keystore 的成功路径。
10. 发布负责人配置真实 GitHub Secrets 后触发 `v0.1.0-m8` 候选，核对远程构件、Release 页面、证书指纹与源码 SHA。
11. 在 PJZ110 安装同一签名 APK，复测启动、四色快速切换演奏、后台恢复和覆盖安装；通过后交给三档设备正式性能矩阵。

## Validation

- `./gradlew -PenableNativeBuild=true :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon --max-workers=1`
- 缺失签名变量时执行 `:app:assembleRelease`，断言清晰失败且没有发布候选。
- 使用已备份的正式专用 keystore 执行 `:app:assembleRelease`。
- `$ANDROID_HOME/build-tools/35.0.0/apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk`
- APK 版本、标签、SHA-256、证书 SHA-256 与提交 SHA 交叉核对。
- GitHub Actions：手动触发只生成 Artifact；`v0.1.0-m8` 标签生成普通公开 Release。

## Risky Files / Rollback Points

- `app/build.gradle.kts`：签名输入判断不得破坏 Debug；出现回归时先回退签名绑定，不回退版本号。
- `.github/workflows/android.yml`：发布权限只放在 release job；创建 Release 失败时保留已验证 Artifact。
- `.gitignore`：只增加精确的签名属性文件规则，禁止用宽泛目录规则隐藏项目配置。

## Start Gate

- [x] 专用 keystore 已生成，且用户确认至少一份离线备份已完成。
- [x] 用户审查规划后明确批准激活任务并开始实现。
- PRD、设计和执行计划经用户批准后，才能激活任务并开始实现签名链路。

## Implementation Status

- [x] 版本号、专用 keystore、离线备份与证书指纹已固化。
- [x] Gradle 本地文件/CI 环境变量双入口与不可绕过的 Release 签名门禁已实现。
- [x] 共享发布校验脚本已验证签名、证书、版本、标签、SHA-256 与构建信息。
- [x] GitHub Actions 四项 Secrets 已配置，工作流已实现签名 Artifact 与 tag 自动公开 Release。
- [x] README 与项目 Release Signing 规范已同步。
- [x] Debug 全量门禁与签名 Release 构建通过。
- [x] 功能分支已推送，GitHub `workflow_dispatch` 运行 `33847138811` 通过；签名 APK、SHA-256 与构建信息已上传并独立下载复核。
- [x] 签名 Release 已安装到 PJZ110；冷启动、MediaPipe 初始化、四色快速切换与声音主观验收、后台恢复均通过。
- [ ] 在验证提交后创建并推送 `v0.1.0-m8` 标签，核对自动公开 GitHub Release。
