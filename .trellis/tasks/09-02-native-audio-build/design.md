# 原生音频构建与兼容回退 — 技术设计

## Boundary

`DrumEngine` 是 Kotlin 侧唯一入口；native 引擎由单一音频回调拥有 voice pool。任何非音频线程只向有界 trigger-command queue 投递命令，绝不直接改写 callback 正在读取的 voice。

## Native Model

- 使用固定容量、不可复制的 voice storage，避免 `std::vector::assign` 与 `std::atomic` 的复制错误。
- callback 在每个 buffer 开始排空命令队列，再混音活跃 voice；满载时按确定策略丢弃最新命令或抢占最旧 voice，并记录计数。
- 采样数据在 `start/setKit` 完成后只读；Kit 切换通过安全暂停/交换或 generation 边界完成，不能让 callback 访问已释放数据。
- 删除不兼容的 `getLatency()` 调用；实现时依据本地 Oboe 1.10 头文件选择可用的 timestamp/延迟诊断 API。

## Kotlin Model

- `start()` 先尝试 native，失败则初始化 SoundPool；明确暴露后端状态。
- `setKit(Kit)` 负责加载完整 pad→sample 映射，任何加载失败不破坏仍可播放的旧 Kit。
- SoundPool 等待样本加载完成后才标记 ready，避免冷启动的静默丢击。

## Validation

构建覆盖 arm64-v8a、armeabi-v7a 和 debug x86_64；真机记录 native 与 fallback 的实际后端与延迟。
