# 技术设计

## 已确认故障链

PJZ110 签名 Release 中，STEP 停止与播放状态均无网格；进入校准只显示外框和角点，
拖动后仍无法保存。OpenCV 已成功加载。`GridProjection.createOrNull()` 将内部异常静默
转成 `null`，`StepSequencerOverlay` 随即提前返回，而 Transport 与投影状态互不约束。

## 设计边界

数据流保持单向：

```text
CalibrationOverlay 角点
  → GridProjection 校验/派生
  → GridScanner 原子发布不可变快照
  → Settings 持久化角点
  → StepSequencerOverlay 绘制 / LivePerformancePipeline 命中查询
```

### 投影

- 将单位正方形与四边形之间的单应矩阵构造收敛在 `GridProjection`。
- 首选纯 Kotlin 的有限、可逆 3×3 单应矩阵计算，移除校准对 OpenCV 初始化时序的依赖，
  并让真实创建入口可由 JVM 测试直接覆盖。
- 创建前校验点数、有限值、边界、面积、相邻边交叉和逆矩阵；失败返回带原因的结果，
  `createOrNull` 只保留为兼容包装，不再成为 UI 的错误信息边界。
- `GridScanner` 只在角点变化时创建并原子发布投影；逐帧读取仍为纯数学映射。

### 校准 UI

- `CalibrationOverlay` 持有未提交角点草稿，预览使用同一投影结果。
- “确认并保存”仅在投影有效时提交并关闭；取消丢弃草稿。
- 默认角点本身必须有效，用户无需为了触发保存而进行无意义拖动。

### STEP 未校准状态

- 网格组件在无投影时显示明确空状态，而不是静默返回。
- 播放按钮在无有效投影时不调用 `Transport.play()`，转而打开校准并提示原因。
- 有效投影发布后立即重组 UI；持久化仍只保存四个归一化角点。

## 兼容与回滚

- Settings schema 不变；已有合法四点数据继续读取，非法数据安全进入未校准状态。
- 不改变 `SequenceState`、Transport 或手势命中协议。
- 若新投影实现出现回归，可单独回退 `GridProjection`，UI 的未校准保护仍可保留。
