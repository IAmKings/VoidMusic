# M4: AR 步进序列器（4×16）

## Goal

实现 4×16 步进序列器：GridScanner（透视网格）→ 步进 UI（点亮/熄灭）→ Transport（节拍时钟）→ 播放循环，完整对齐网页版 AR Step Sequencer 玩法。

覆盖 PRD F3。工期 2 周。

## Requirements

### R4.1 GridScanner 透视网格（PRD F3.1-F3.2）

- 输入：M2 中的 4 点校准结果。
- 使用 `getPerspectiveTransform` 计算 `src → dst` 透视矩阵。
- 将 4×16 网格的每个格中心点通过矩阵映射到取景器坐标。
- 接口：`getCellCenters()` 返回 `Array<List<Point>>` `[row][step]`。
- 接口：`locateCell(point)` 返回手指命中格位 `(row, step)` 或 null。

### R4.2 步进网格 UI（PRD F3.3）

- 透视网格叠加层绘制在取景器上，4 行 × 16 列。
- 每格可点击切换点亮/熄灭状态。
- 行颜色对应鼓声（如 Kick=红、Snare=蓝、Clap=绿、Tom=黄）。
- 当前播放步高亮扫描（与 Transport 联动）。

### R4.3 Transport 节拍时钟（PRD F3.4-F3.5）

- 基于 Oboe 回调驱动的精准节拍时钟（对齐网页版 Tone.Transport）。
- 速度可调：BPM 范围 60~200（默认 120）。
- 播放/停止控制。
- 每步 tick 时检查 `SequenceState.grid[row][step]`，对点亮行调用 `DrumEngine.trigger`。
- 当前步索引通过 `StateFlow<Int>` 暴露给 UI。

### R4.4 序列状态模型

```kotlin
data class SequenceState(
    val rows: Int = 4,
    val steps: Int = 16,
    val grid: List<List<Boolean>>,    // grid[row][step]
    val bpm: Int = 120,
    val currentStep: Int = 0,
    val isPlaying: Boolean = false
)
```

### R4.5 步进交互

- 手指触碰网格区域 → `PadTracker` 判定命中格位 → 切换点亮/熄灭。
- 编辑可在播放中进行（实时变奏）。
- 清空按钮一键清除所有步进。
- 步进编辑时视觉即时反馈（格闪烁或高亮）。

## Acceptance Criteria

- [ ] 4×16 网格经透视变换准确叠加在校准纸面区域
- [ ] 手指点击格子正确切换点亮/熄灭（命中准确率 > 90%）
- [ ] Transport 播放时当前步逐格扫描，视觉可辨识
- [ ] 当前步命中已点亮格 → 对应鼓声触发（无遗漏、无重复）
- [ ] BPM 60 和 200 两端均可正常播放，节拍速度听觉可辨识
- [ ] 播放中实时编辑格子：点亮新格下一轮生效，熄灭已亮格立即静默
- [ ] 播放/停止切换流畅，无音频残留
- [ ] 序列器与实时击打模式可切换，切换后各自状态独立保持

## Out of Scope

- 节拍器音（Click Track，P2）
- 序列预设保存（M5）
- Swing / Shuffle 节奏（后续迭代）

## Dependencies

- 依赖 M0：CameraX + 基础架构
- 依赖 M2：4 点透视校准 + DrumZone
- 依赖 M3：DrumEngine 音频引擎 + Transport 基础
- 推荐先完成 M2、M3 再做 M4
