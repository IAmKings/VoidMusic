# 实施计划

## 1. 锁定回归

- [x] 为 `GridProjection.createOrNull` 增加默认矩形、透视四边形、退化和自交输入测试；先确认当前默认矩形失败。
- [x] 为无投影网格空状态、默认校准确认与取消行为增加 Compose 测试。

## 2. 修复投影契约

- [x] 实现可测试的一次性单应矩阵构造和求逆，验证有限值、面积与边交叉。
- [x] 生成并验证 64 个单元中心，保持 `locateCell` 的边界语义。
- [x] 移除静默失败，向校准 UI 提供稳定错误原因。

## 3. 修复校准与播放流程

- [x] 校准层增加确认/取消，默认角点可直接保存，非法草稿保留在界面并提示。
- [x] 无校准 STEP 显示行动提示，播放操作打开校准且不启动 Transport。
- [x] 发布有效投影后立即显示网格，并保持 DataStore 冷启动恢复。

## 4. 完整验证

- [x] 运行定向 JVM/Compose 测试、完整单元测试、Lint 和 Debug 构建。
- [x] 安装 Debug 到 PJZ110，执行三次完整校准/播放复现循环与冷启动恢复。
  已完成独立 Debug 包的 27 条仪器测试、未校准状态验证、签名 Release 的三轮冷启动恢复，
  并由用户确认最终正式测试包可正常播放。
- [x] 清理诊断资源，更新 camera-vision spec，复核实时击打与音频路径无改动。

## 风险点

- `GridProjection` 数学错误会同时影响绘制和手势命中，必须用正反映射与边界用例约束。
- Compose 草稿不得在拖动过程中写 DataStore；只有显式确认才提交。
- 播放保护不得影响已有合法校准用户或正在运行序列的停止操作。

## 验证命令

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest
```
