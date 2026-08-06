# Object Drum Studio

AR 桌面鼓机 — 用手机摄像头识别桌面上的彩色物体和手部动作，实时触发鼓声。纯本地处理，无需网络。

## 功能

- **实时击打模式** — 手指点击彩色物体触发对应鼓声（底鼓/军鼓/拍手/嗵鼓/踩镲）
- **AR 步进序列器** — 在纸上画 4×16 网格，手指点击格子编排节奏循环
- **手部追踪** — MediaPipe HandLandmarker 实时追踪指尖位置
- **颜色分割** — OpenCV HSV 检测彩色物体并映射到鼓垫
- **低延迟音频** — Oboe 原生音频引擎（<40ms）或 SoundPool 降级方案
- **三档性能** — 高/中/低性能档位，适配不同机型

## 系统要求

- Android 8.0 (API 26) 及以上
- 后置摄像头
- 推荐 arm64-v8a 设备（骁龙 6 系及以上）

## 技术栈

| 模块 | 技术 |
|------|------|
| UI | Jetpack Compose + Material 3 |
| 相机 | CameraX (Preview + ImageAnalysis) |
| 手部追踪 | MediaPipe Tasks Vision (HandLandmarker) |
| 颜色检测 | OpenCV 4.x (HSV + 连通域) |
| 音频引擎 | Oboe (AAudio) → SoundPool 降级 |
| 持久化 | DataStore Preferences + kotlinx.serialization |
| 构建 | Gradle KTS + Version Catalog |

## 项目结构

```
app/src/main/java/com/electrodig/objectdrumstudio/
├── audio/          # 音频引擎 (Oboe JNI / SoundPool / WAV解码 / Transport时钟)
├── camera/         # CameraX 封装 (取景器 / 帧路由)
├── detection/
│   ├── color/      # OpenCV HSV 颜色分割
│   ├── grid/       # 透视网格 / 序列器状态
│   ├── hand/       # MediaPipe 手部追踪
│   └── hit/        # 击打检测 / 仲裁 / 鼓垫追踪
├── persistence/    # 设置持久化 & 性能档位配置
├── session/        # ViewModel & UI 状态
└── ui/
    ├── screens/    # 主屏幕 / 设置 / 引导
    ├── components/ # HUD / 叠加层 / 控件
    └── theme/      # Material 3 主题
app/src/main/cpp/   # Oboe 原生音频 (CMake)
app/src/main/assets/ # MediaPipe 模型
app/src/main/res/   # 资源 (鼓声样本 / 图标)
```

## 构建

### 前置条件

- Android Studio Meerkat (2024.3+) 或更高
- JDK 21 (随 Android Studio 内置)
- 如需原生音频：SDK Manager 安装 NDK 27.2.12479018

### 命令

```bash
# Debug 构建 (含所有 ABI，适合模拟器测试)
./gradlew assembleDebug

# Release 构建 (仅 arm64-v8a，APK ~44MB)
./gradlew assembleRelease

# 运行单元测试
./gradlew testDebugUnitTest
```

### 启用原生音频 (Oboe)

在 `gradle.properties` 中设置：
```properties
enableNativeBuild=true
```
需先通过 SDK Manager 安装 NDK 27.2.12479018。未启用时自动使用 SoundPool 降级方案。

## 性能档位

| 档位 | 相机分辨率 | 分析帧率 | MediaPipe | 手部数 | 降采样 |
|------|-----------|---------|-----------|-------|--------|
| HIGH | 1080p | 30 FPS | GPU | 2 | 1x |
| MEDIUM | 720p | 20 FPS | GPU | 2 | 0.5x |
| LOW | 480p | 15 FPS | CPU | 1 | 0.25x |

省电模式或设备过热时自动切换至 LOW 档位。

## 隐私

- 不申请 `INTERNET` 权限
- 摄像头画面不写入磁盘、不上传
- 所有处理（视觉识别 + 音频）均在本地完成

## License

Proprietary — Electro-Dig.
