# M2: HSV 物件识别 + 4 点透视校准

## Goal

实现 ColorSegmenter（HSV 分割 + 连通域提取）、4 点透视校准界面、DrumZone 可视化叠加渲染、取色器交互。完成 PRD F4 和 F1.4-F1.5。

工期 1.5 周。

## Requirements

### R2.1 HSV 色彩分割（PRD F4.1-F4.3）

- 实现 `ColorSegmenter` 模块（对齐网页版 `detection/colorSegment`）：
  1. CameraX 帧（RGBA）→ 转 HSV。
  2. 对每组 `HsvPreset` 做 `inRange` 二值化。
  3. 形态学开/闭运算去噪（erode + dilate）。
  4. `connectedComponentsWithStats` 提取连通域。
  5. 按面积 / 长宽比过滤噪点，输出 `DrumZone[]`。
- 数据结构：`DrumZone`（id, contour, center, area, hsvColor, mappedPad）。
- 支持多组 HSV 预设（红/蓝/绿/黄等）。

### R2.2 实时 HSV 阈值调节（PRD F4.1）

- UI 提供 H/S/V 上下限滑杆（6 条酒吧滑块）。
- 实时调节时识别结果即时更新（帧间生效）。
- 可选：预设快速切换按钮（红/蓝/绿/黄一键填充 HSV 范围）。

### R2.3 取色器（PRD F4.2）

- 在取景器上点选像素 → 自动读取该位置 HSV 值。
- 以选取值为中心扩展生成 `HsvRange`（上下限 ± buffer）。
- 取色时暂停帧冻结或高亮定位点。

### R2.4 4 点透视校准（PRD F1.4）

- 校准浮层：4 个可拖拽角点覆盖在取景器上。
- 用户对齐纸面/桌面四角 → 确认 → 计算透视变换矩阵。
- 矩阵用于后续 GridScanner（M4）的坐标系映射。
- 校准结果可保存为预设（M5 做预设管理）。

### R2.5 DrumZone 可视化（PRD F4.5）

- 在取景器上实时绘制识别到的物件轮廓 + 编号。
- 轮廓使用对应 HSV 预设的近似颜色绘制。
- 显示物件中心点标记。

## Acceptance Criteria

- [ ] ColorSegmenter 在 640×480 帧上稳定输出 ≥ 1 个 DrumZone（有色物件场景）
- [ ] HSV 滑块调节时识别结果即时变化（< 100ms 响应）
- [ ] 取色器点击后自动填充 HSV 范围，该区域被识别为 DrumZone
- [ ] 4 点校准：拖拽四角无卡顿，确认后坐标系可用
- [ ] DrumZone 轮廓与编号在取景器上正确叠加渲染
- [ ] 多颜色物件（如红+蓝）分别识别为不同 DrumZone
- [ ] 过滤掉明显噪点（面积过小/长宽比极端）

## Out of Scope

- 击打触发（M3）
- 步进序列器（M4）
- 校准预设保存/管理（M5 预设管理，P2）

## Dependencies

- 依赖 M0：工程脚手架 + CameraX
- 依赖 M1（部分）：手部追踪的坐标系映射可复用，但可选独立先做
- OpenCV Android SDK 的集成（M0 应已引入依赖）
