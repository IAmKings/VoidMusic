# 发布性能与真机验收 — 技术设计

## Measurement Protocol

每次测量固定记录构建 SHA、APK hash、性能档位、机型、系统、环境温度和相机摆位。高速摄像/波形测量击打到出声；Oboe 后端使用已验证的诊断记录流延迟；Profiler 记录峰值内存。

## Device Matrix

- 高端：骁龙 8 Gen / 天玑 9300 级别，目标 ≥60 FPS、<80ms。
- 中端：骁龙 7/6 / 天玑 7000 级别，目标 ≥30 FPS、<100ms。
- 低端：4GB RAM 入门设备，允许降级但目标 <150ms。

## Release Gate

所有行必须有原始记录和 Pass/Fail。缺设备、缺测量、超阈值、release 构建失败或隐私不通过均为 Fail，不得被平均值掩盖。
