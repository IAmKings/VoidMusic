# 相机坐标与击打链路正确性

## Goal

使手部、鼓区、校准网格和命中点在 PreviewView 中使用同一坐标系，并让相机分析线程安全、击打语义可验证。

## Confirmed Facts

- FrameRouter 仅旋转图像；PreviewView 的缩放与裁剪未映射到 Compose 覆盖层。
- 分析线程写入 `mutableStateOf`、调用触觉反馈，违反 UI 线程边界。
- DrumZone 每帧从零分配 ID，HitArbiter 的冷却状态会错误关联不同物件。
- HitDetector 输出固定 velocity，且不体现 PRD 所需的下落速度差异。

## Requirements

- 建立可测试的 ImageAnalysis → PreviewView/Compose 坐标变换，覆盖竖屏、横屏、旋转与裁剪。
- 分析线程仅产生不可变检测事件；Compose state、动画和触觉反馈在主线程消费。
- 引入跨帧 ZoneTracker，为同一物件维护稳定 ID，并在消失后按明确 TTL 回收。
- HitDetector 以真实时间差计算速度，并保留“向下击打”判定与每指冷却；HitArbiter 继续支持多指/多区。
- 校准网格、鼓区和手部覆盖层都使用统一的映射结果。

## Acceptance Criteria

- [ ] 竖屏与横屏真机中，手指覆盖、鼓区覆盖和实际触点的偏差处于预先定义的可接受阈值内。
- [ ] 旋转、裁剪和预览重绑的纯逻辑拥有单元/仪器测试。（旋转与裁剪 JVM 测试已覆盖；预览重绑仍待仪器测试）
- [x] 两个稳定物件跨连续帧保持 ID；失踪/重现行为可预测。
- [x] 不同击打速度产生不同 velocity；悬空和横向移动不误触。
- [ ] 分析线程不再直接写 Compose snapshot 或调用 View；压力运行无线程异常。（代码路径已隔离；仍待真机压力运行）

## Out of Scope

- 新的识别模型、前置镜像模式、P2 手势与视觉重设计。

## Dependencies

- 可与音频任务并行；完成后为功能和发布验收提供可信输入。
