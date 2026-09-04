# 签名 Release 与自动发布闭环

## Goal

让 Void Music 能从同一源码提交稳定生成可验证、可安装、可追溯的签名 Release APK，并在版本标签触发后自动创建 GitHub 候选版本，为三档真机正式复测提供唯一可信构建物。

## Confirmed Facts

- 应用版本已从 `versionCode=2 / versionName=0.1.0-m7` 递增为 `versionCode=3 / versionName=0.1.0-m8`。
- 当前 `release` 仅包含 `arm64-v8a`，启用 R8 与资源压缩；最近未签名 Release APK 约 44.6 MiB，满足 `<80 MB` 门槛。
- 当前 GitHub Actions 在 `v*` 标签或手动触发时只构建并上传 `app-release-unsigned.apk`，没有签名校验、版本标签校验、校验和或 GitHub Release 创建步骤。
- 当前工作流全局权限为 `contents: read`，不能创建 GitHub Release。
- 仓库已忽略 `*.jks` 和 `*.keystore`，但尚未定义本地 `keystore.properties` 与 CI Secrets 的统一字段契约。
- 高、中、低三档正式性能矩阵尚未完成，因此首个自动发布产物不能宣称为正式稳定版。
- `0.1.0-m8` 处于内测阶段；当前优先建立可靠的全自动签名发布能力，三档设备验收硬门禁延后到稳定期启用。
- 当前没有外部用户或已分发的正式签名 APK，不需要兼容历史签名证书；本轮生成第一套 Void Music 专用 Release keystore，作为后续覆盖升级的长期身份。
- 专用 keystore 已生成到仓库外的 `~/.config/void-music/signing/`，目录、私钥和密码文件权限均限制为仅当前用户访问；用户已确认完成离线备份。

## Requirements

- Gradle Release 签名只从未跟踪的本地属性或环境变量读取，不得在源码、构建日志、Actions 构件或缓存中泄露证书和密码。
- 统一支持四项签名输入：keystore 路径、keystore 密码、key alias、key 密码；缺项时 Release 构建必须给出清晰错误，Debug 构建不受影响。
- GitHub Actions 从 Secrets 在 Runner 临时目录恢复 keystore，并在作业结束后依赖临时 Runner 销毁；仓库不得保存 keystore 内容。
- `v*` 标签必须与 APK 的 `versionName` 一致，例如本轮标签为 `v0.1.0-m8`；不一致时停止发布。
- Release 构建后必须用 `apksigner verify --verbose --print-certs` 校验，并记录证书 SHA-256 指纹、APK SHA-256、`versionCode`、`versionName` 和源码提交 SHA。
- 上传命名稳定的签名 APK、SHA-256 文件和构建信息；不得再把 unsigned APK 当作发布候选。
- 标签触发时自动创建 GitHub Release；手动触发只用于验证和下载签名构件，不应意外创建公开版本。
- GitHub Release 必须是普通公开版本，不使用 Pre-release 或 Draft，也不需要人工点击发布。
- 内测阶段不读取三档设备验收清单作为发布硬门禁；签名、版本、测试和构建检查通过即可发布。进入稳定期后必须另行增加设备验收硬门禁。
- GitHub Release 仅授予发布作业 `contents: write`，测试作业和普通分支仍保持最小只读权限。
- 更新仓库文档，说明 Secrets 名称、标签规则、密钥轮换与丢失风险，不写入任何真实密钥值。

## Acceptance Criteria

- [x] `versionCode=3`、`versionName=0.1.0-m8`，Debug 与 Release 均读取到一致版本。
- [x] 无签名输入时 Debug 全量质量门禁通过，Release 明确失败且不生成伪候选包。
- [x] 使用已备份的正式专用 keystore 可在本地生成 `app-release.apk`，`apksigner` 验证成功且证书指纹匹配任务记录。
- [x] 首套正式专用 keystore 已生成、权限受限并完成离线备份。
- [x] CI 的四项签名 Secret 均只在 Release 作业使用，日志与构件中无密钥或密码。
- [x] 标签与版本不一致时发布校验阻止发布；`v0.1.0-m8` 可生成签名 APK、SHA-256 与构建信息。
- [ ] 标签流程全自动创建普通公开 GitHub Release 并上传完整产物，不标记 Pre-release/Draft；手动流程只上传 Actions 构件。
- [x] 内测标签不因三档设备数据未齐而失败；发布说明明确标注当前为内测里程碑，不伪称稳定版。
- [ ] 签名 APK 在 PJZ110 安装、启动、四色快速切换演奏和后台恢复正常。
- [x] 单元测试、Lint、Debug 构建和启用原生音频的 Release 构建全部通过。

## Constraints

- 当前内测阶段不设置三档设备硬门禁；进入稳定期后再启用该门禁。任何阶段都不得在文档和构件元数据中伪造未完成的验收结论。
- 正式 keystore 及密码必须由发布负责人保管和配置，开发代码不能生成或替换正式密钥。
- 本轮经发布负责人明确授权生成第一套正式专用 keystore；生成后必须仓库外保存、限制文件权限并完成至少一份离线备份。
- 保持 API 26+、纯本地运行、无 `INTERNET` 权限以及 Release APK `<80 MB`。

## Out of Scope

- Google Play Console、Play App Signing、AAB 上传和商店审核。
- 在 CI 中自动生成或轮换正式签名密钥、跨组织密钥托管或第三方密钥管理服务。本轮经用户授权的一次性首套密钥生成不在此限制内。
- 本任务不执行完整高/中/低三档 20 分钟性能矩阵；它只提供该矩阵使用的唯一签名候选包。

## Decisions

- 生成新的 Void Music 专用 Release keystore；不存在历史外部安装，后续所有可升级 Release 必须持续使用该证书。
- 内测阶段普通公开 Release 不设置三档设备硬门禁，稳定期再启用。

## Signing Identity

- Alias：`void-music-release`
- Store type：PKCS12
- Algorithm：RSA 4096 / SHA256withRSA
- Validity：2026-09-04 至 2054-01-20
- Certificate SHA-256：`D1:51:A2:61:C0:CB:73:86:F9:FF:8E:29:91:55:38:C3:7F:4C:88:0E:7F:28:8E:36:8B:90:FC:D3:DB:EC:ED:59`
- Keystore file SHA-256：`a6bff9c21a4f10eaea1fde1135d5a3b381e6b25199a6cbe5b135aae256729575`

## Local Verification Evidence

- 最终修复后的签名 Release：`app-release.apk`，46,901,995 bytes（约 44.7 MiB），低于 80 MB 门槛。
- 最终本地 APK SHA-256：`32036741e72b4ba3fa5da30b8e8d1d60ecf1afdeceff33b19ca1e233cb5a17ea`。
- `apksigner`：v2 签名验证通过，1 个 signer，证书 SHA-256 与 Signing Identity 一致。
- APK 元数据：`versionName=0.1.0-m8`、`versionCode=3`；`v0.1.0-m8` 通过，错误标签以退出码 65 阻断。
- 缺少本地签名配置时 `preReleaseBuild` 明确失败；通用 `assemble` 依赖图包含同一门禁，不能绕过。
- Gradle 配置缓存可复用，并能在 `keystore.properties` 新增/移除时正确失效。
- GitHub 仓库已配置四项 Actions Secrets；CLI 只验证名称与更新时间，未读取或输出远程值。
- GitHub `workflow_dispatch` 运行 `33847138811` 全部通过；Debug 门禁与签名 Release 作业分别通过，手动流程按设计跳过公开 Release。
- 远程构件 `void-music-release-8` 已独立下载复核：ZIP 完整、APK SHA-256 为 `18c6d33fcb3a74659483ea3b93f2414b1e575884a4aa82ddfef04ac8643255f8` 且 `SHA256SUMS` 校验通过；`BUILD-INFO.txt` 指向提交 `86bf15a196e900f587182086ddf996bb250a90e6`，APK 为 `versionName=0.1.0-m8`、`versionCode=3`。
- 远程 APK 经本地 `apksigner` 二次验证：v2 签名有效、仅 1 个 signer、RSA 4096，证书 SHA-256 与 Signing Identity 一致。
- PJZ110 真机暴露并验证修复了两项仅在压缩 Release 出现的问题：Flogger 调用栈被 R8 改写导致 MediaPipe `Graph` 初始化崩溃；Protobuf Lite 字段名被改写导致手部模型 GPU/CPU 均初始化失败。
- 最终修复包冷启动超过原 15 秒崩溃窗口后进程存活、`MainActivity` 保持前台、crash 缓冲区为空且 `HandTracker` 无初始化错误；进入后台再恢复后 PID 保持不变且无新增错误。
