# 相机坐标与击打链路正确性 — 技术设计

## Coordinate Contract

检测层先使用分析图像归一化坐标；单独的 mapper 根据 CameraX crop、旋转和 PreviewView output transform 转为 Compose 覆盖层坐标。手部、鼓区、校准角点和网格只能通过该 mapper 渲染/命中，不得各自补偿。

## Thread Boundary

FrameRouter/分析 executor 持有普通不可变快照或线程安全流；ViewModel/Compose 在主线程将其转为 UI state。触觉反馈以 UI effect 事件发送并在主线程执行。

## Detection State

ZoneTracker 按同预设 + IoU/中心距离把新 contour 匹配到既有 track，发出稳定 ID；track 在有限 TTL 后移除。HitDetector 以相邻真实时间戳计算速度，结合向下方向、每指 tracker 和冷却窗口；HitArbiter 使用稳定 zone ID。

## Tests

纯 mapper、ZoneTracker、速度和冷却为 JVM 单测；PreviewView 变换、旋转和主线程触觉走仪器/Compose 测试；真机验证负责最终视觉偏差。
