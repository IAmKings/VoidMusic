# 视觉识别可靠性优化 — 设计

## 数据流

```
CameraX frame(timestamp)
  ├─ 节流后提交 HandLandmarker
  │    └─ callback → TimestampedHands(timestamp, raw/smoothed hands)
  └─ 节流后 HSV segmentation → TimestampedZones(timestamp, stable zones)

TimestampedHands + 最近且未过期的 TimestampedZones
  → HitDetector → HitCandidate（带来源手指/时间）
  → HitArbiter / GridScanner → audio 或 step toggle
```

`rawHands` 继续用于不被平滑削弱的击打；但击打计算只在对应 MediaPipe 回调的时间戳上执行。分区缓存仍允许低频更新，但需记录更新时间并设置有限有效期。

## 资源与性能

- `ColorSegmenter` 持有一个可复用的椭圆 morphology kernel，并实现 `close()` 显式释放；分割器重建或页面销毁时关闭。
- 帧率限制在 FrameRouter 中基于单调时间实现；未到间隔的帧仍安全关闭 `ImageProxy`，不提交给昂贵消费者。
- 摄像头预览不被限制；限制的是分析链路。颜色分割保持低于或等于手部分析频率，并可使用独立节流策略。
- 增加低成本运行指标：提交分析 FPS、手部结果 FPS、色块分割耗时、缓存鼓区年龄、输入帧丢弃数。

## 命中与取色

- `PadTracker` 对所有包含候选点的鼓区按中心距离排序，避免依赖 contour 输出顺序；必要时再使用中心半径兜底。
- `HitCandidate` 携带来源 fingertip/hand track 标识和点位；STEP 直接使用候选点定位格子。
- 取色优先采用中心区域的中位数与分位数范围，忽略低饱和样本，避免边缘背景把 S/V 拉至极值。

## 验证策略

- 单元测试：节流、结果时间戳、候选点到 STEP、重叠鼓区排序、稳健 HSV 范围。
- 真机：固定四区，正光/阴影/背光、单手/双手、横竖屏；高/中/低档记录 P50/P95 指标。
- 长稳：20 分钟演奏检查 native heap、PSS、帧率、掉帧和误触。
