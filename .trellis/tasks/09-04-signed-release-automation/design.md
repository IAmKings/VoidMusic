# 签名 Release 与自动发布闭环 — 技术设计

## 构建边界

签名配置集中在 `app/build.gradle.kts`。Gradle 优先读取根目录未跟踪的 `keystore.properties`，CI 则使用等价环境变量；二者映射为同一组内部字段。只有请求 Release 任务时才校验字段完整性并绑定 `signingConfig`，避免普通 Debug 开发依赖发布密钥。

本地属性约定：

- `storeFile`
- `storePassword`
- `keyAlias`
- `keyPassword`

CI 环境变量约定：

- `VOID_MUSIC_STORE_FILE`
- `VOID_MUSIC_STORE_PASSWORD`
- `VOID_MUSIC_KEY_ALIAS`
- `VOID_MUSIC_KEY_PASSWORD`

`keystore.properties`、keystore 文件和任何临时解码文件必须保持未跟踪。Gradle 错误只能列出缺失字段名，禁止输出已读取值。

## CI 数据流

```text
GitHub Secrets
  → Runner 临时目录恢复 release.jks
  → 环境变量传入 Gradle
  → assembleRelease 生成 app-release.apk
  → apksigner 验证证书与 APK 完整性
  → aapt/dumpsys 读取版本 + 校验标签
  → sha256sum + 构建信息清单
  → Actions Artifact
  → 仅 tag 事件创建普通公开 GitHub Release 并上传产物
```

Secrets 使用以下名称：

- `ANDROID_RELEASE_KEYSTORE_BASE64`
- `ANDROID_RELEASE_STORE_PASSWORD`
- `ANDROID_RELEASE_KEY_ALIAS`
- `ANDROID_RELEASE_KEY_PASSWORD`

Base64 内容只写入 `$RUNNER_TEMP/void-music-release.jks`。发布作业单独声明 `permissions: contents: write`；验证作业继续使用只读权限。

## 版本与发布契约

- APK `versionName` 是版本唯一来源，标签格式为 `v<versionName>`。
- 本轮候选为 `v0.1.0-m8`，APK `versionCode=3`。
- tag 事件必须严格校验标签与 APK 元数据；手动触发不创建 GitHub Release。
- GitHub Release 不使用 Pre-release 或 Draft；标签流程完成校验后直接公开，无需人工发布。
- `0.1.0-m8` 内测阶段的自动门禁为：单元测试、Lint、Debug/Release 原生构建、签名验证、版本标签一致性和校验和；不读取三档设备验收结果。
- 进入稳定期后在发布作业前增加独立的设备验收清单检查，本任务仅预留清晰插入点，不提前实现。
- 构件固定包含签名 APK、`SHA256SUMS` 和包含版本、提交、证书指纹的文本清单。

## 安全与失败策略

- 首套正式密钥使用 RSA 4096 / SHA256withRSA，alias 为 `void-music-release`，有效期 10,000 天；文件保存在仓库外的用户私有目录并限制为仅当前用户访问。
- 已生成的证书 SHA-256 为 `D1:51:A2:61:C0:CB:73:86:F9:FF:8E:29:91:55:38:C3:7F:4C:88:0E:7F:28:8E:36:8B:90:FC:D3:DB:EC:ED:59`；后续本地构建、GitHub Secrets 和发布 APK 必须匹配该身份。
- 任一 Secret 缺失、Base64 解码失败、签名失败、证书校验失败或标签不一致时，发布作业立即失败。
- 不回退到 unsigned APK，不把 Debug 签名用作 Release 签名。
- Actions 缓存不得包含 Runner 临时 keystore；上传路径只匹配最终 APK 和文本清单。
- 正式签名密钥丢失会影响后续覆盖升级，仓库文档必须要求离线备份并记录证书指纹。

## 兼容与回滚

- Debug、PR 和普通 `master` push 的质量门禁保持现状，不需要签名 Secrets。
- 若自动发布步骤异常，可关闭 GitHub Release 创建步骤，但保留签名构建、验证和 Actions Artifact，避免回退到未签名候选。
- 如果密钥需要轮换，必须提高 `versionCode` 并评估现有安装覆盖兼容性；未经确认不得直接替换正式证书。
- 内测阶段不设置三档设备验收硬门禁；未来稳定期门禁不得削弱现有签名、版本、校验和与最小权限设计。
