# M1: 摄像头取景 + 手部追踪打通

## Goal

MediaPipe HandLandmarker 集成到 CameraX 图像分析管道，实现食指指尖（landmark 8）实时追踪并在取景器上叠加渲染，完成归一化坐标到取景器像素坐标的映射。

工期 1.5 周。

## Requirements

### R1.1 HandLandmarker 接入（PRD F5.1）

- 使用 MediaPipe Tasks Android `HandLandmarker`：
  - `numHands = 2`（支持双手）。
  - `runningMode = LIVE_STREAM`（实时流模式）。
  - 模型文件打包为 `raw` 资源。
- 从 CameraX `ImageAnalysis` 的 `ImageProxy` 提供帧数据。
- 输出 21 关键点的归一化坐标 `[0, 1]`。

### R1.2 关键点选取（PRD F5.2 部分）

- 主要触发点：关键点 **8（食指指尖）**。
- 辅助判定：关键点 **5（食指近端）**，用于方向判定。
- 归一化坐标映射到取景器像素坐标（考虑 PreviewView 缩放/裁剪）。

### R1.3 指尖叠加渲染

- 在取景器叠加层上实时绘制指尖位置（十字准心或圆点）。
- 可选：双手时不同颜色区分（左手蓝、右手红）。
- 渲染使用 Compose Canvas 绘制在 `CameraPreview` 上方。

### R1.4 性能监控

- 追踪推理帧率（FPS 计数器，可隐显切换到 HUD）。
- 记录 HandLandmarker 推理耗时（毫秒/帧）。

## Acceptance Criteria

- [ ] HandLandmarker 在真实设备上正常初始化（需有摄像头设备测试）
- [ ] 手指在摄像头视野内时，指尖位置实时叠加渲染在取景器上
- [ ] 手部追踪帧率 ≥ 25 FPS（中端机及以上）
- [ ] 竖起 1 根手指（食指）稳定追踪，无明显丢帧
- [ ] 归一化坐标到像素坐标映射准确（与视觉效果一致）
- [ ] CameraX + HandLandmarker 联调无崩溃、无内存泄漏
- [ ] 进入后台后 HandLandmarker 正确释放资源

## Out of Scope

- 击打检测 / 稳定器 / 仲裁（M3）
- 物件识别（M2）
- 多手击打并发处理（M3 P1）

## Dependencies

- 依赖 M0：工程脚手架、CameraX 取景器、权限流程
