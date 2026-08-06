# M0: 项目脚手架、权限、最小可运行

## Goal

搭建安卓原生工程骨架：Compose 单 Activity + CameraX 取景器 + 摄像头权限流程 + 冷启动引导页。

工期 1 周。

## Requirements

### R0.1 工程脚手架

- 使用 Kotlin DSL (Gradle 8.x)，`minSdk = 26`，`targetSdk = 34`。
- ABI: `arm64-v8a`（优先），`armeabi-v7a`（可选）。
- 单 Activity 架构（`MainActivity`），导航使用 Compose Navigation。
- 引入依赖：Compose BOM 2024.x、CameraX 1.3.x、Hilt 2.x、Coroutines 1.8.x。

### R0.2 CameraX 取景器

- 使用 `PreviewView` 展示取景器，后置摄像头优先。
- `ImageAnalysis` 采集帧（后续 M1/M2 使用），默认 640×480 分辨率。
- 支持竖屏为主，预留横屏适配。

### R0.3 权限流程（PRD F1.1-F1.3）

| 编号 | 功能点 | 描述 |
|------|--------|------|
| F1.1 | 冷启动引导 | 首次启动展示 3~4 屏玩法介绍，说明需要摄像头与桌面物件 |
| F1.2 | 摄像头权限申请 | 运行时申请 `CAMERA` 权限；被拒后引导到系统设置 |
| F1.3 | 姿势引导 | 引导用户将手机俯拍桌面（约 30°~60°），取景器显示对齐框 |

### R0.4 基础架构

- Hilt 依赖注入配置完成（`@HiltAndroidApp`, `@AndroidEntryPoint`, `@Module`）。
- 基础目录结构按 PRD §5.3 建立：`app/`, `detection/`, `audio/`, `camera/`, `persistence/`, `ui/`。
- `AndroidManifest.xml` 声明 `CAMERA` 权限（不声明 `INTERNET`）。
- 生命周期管理：进入后台时暂停摄像头。

## Acceptance Criteria

- [ ] `./gradlew assembleDebug` 构建通过
- [ ] App 启动后显示引导页（首次）
- [ ] 摄像头权限弹窗正确弹出
- [ ] 权限被拒后弹出引导设置对话框
- [ ] 取景器正常显示摄像头预览流
- [ ] 页面导航：引导页 → 权限 → 主界面流程可走通
- [ ] 竖屏预览无变形，至少适配 5.0"~7.0" 屏幕
- [ ] 进入后台后摄像头正确释放

## Out of Scope

- 手部追踪 / 物件识别（M1/M2）
- 音频引擎（M3）
- 任何 UI 控件（HSV 滑块等，后续里程碑）

## Dependencies

- 无前置依赖（项目起点）
- 为 M1-M6 提供工程基础和权限流程
