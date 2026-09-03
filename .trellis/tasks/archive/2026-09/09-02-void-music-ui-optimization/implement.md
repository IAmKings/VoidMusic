# Void Music UI 优化 — 实施计划

1. 读取 UI 层规范与当前 Compose 组件，确认 HUD、HSV、序列控制和校准的现有回调契约。
2. 新增或调整轻量 TAP 控制坞；把 HSV 完整控件移入 Material 3 bottom sheet，默认关闭。
3. 重构 `HudPanel`、右侧快捷操作与 `SequencerControls` 的密度、间距、对比度和无障碍描述，不改业务回调。
4. 检查窄屏布局、取色交互、模式切换、校准、设置导航和步骤播放，修复任何遮挡或点击冲突。
5. 运行 JVM 测试、Lint、debug APK 构建，在已连接真机截图核对实时击打和步进界面。

## 验证

- `./gradlew -PenableNativeBuild=true :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon`
- 真机：实时击打默认界面、打开/关闭 HSV sheet、取色、切换颜色、校准、设置、切到步进模式并播放。
- 使用 1080×2400 级窄屏截图确认：无控件裁切、无遮挡关键鼓区、文字和状态可读。

## 回退

- 将视觉组件回退到既有布局即可；不涉及数据库、会话数据和检测/音频业务状态迁移。

## 执行记录（2026-09-02）

- 新增 `TapControlDock`：实时击打默认只显示当前颜色/鼓声和“调参”入口；HSV 预设与三组范围控件移入按需打开的 Material 3 bottom sheet。
- `HudPanel` 的状态指标改为可横向滚动，避免窄屏截断；右侧校准/设置从带文字的 Extended FAB 收紧为带无障碍描述的图标快捷键；步进控制坞与 TAP 控制坞统一圆角与层级。
- 取色进入模式时会关闭参数 sheet，并在取景器上显示“点按取景器中的目标颜色”提示，确保全屏点击仍送往既有采样逻辑。
- `./gradlew -PenableNativeBuild=true :app:testDebugUnitTest :app:lintDebug --no-daemon --max-workers=1` 通过；强制重新打包后已安装到 PJZ110 真机。
- 真机截图与语义树已确认：默认演奏态显示“当前鼓区 / 调参”，HSV sheet 可显示颜色预设、取色按钮和 H/S/V 三组范围。设备上的外部悬浮层会拦截 ADB 注入点击，未能自动完成步进模式点击复验；本轮没有改动步进业务逻辑，仍需人工点按一次确认。
- 修复引导页在系统浅色主题下的对比度：该页始终使用深色背景，标题和说明文字改为显式的 `OnSurfaceDark` 与 `OnSurfaceMuted`，不再跟随浅色主题取得深色文字。重新安装后已在 PJZ110 真机核对首屏和第三页，标题及说明均清晰可读。
- 引导完成状态改为在现有 DataStore 中独立持久化；启动会等待该状态读取完毕后选择引导页或主界面，避免默认值导致引导闪现。设置页新增“查看使用指南”，恢复默认设置不会清除引导完成标记。
- 新增 `DestinationsTest` 覆盖首次与已完成引导的首屏路由；`testDebugUnitTest`、`lintDebug`、debug APK 构建均通过。PJZ110 真机已验证首次引导、点击“跳过”后的冷启动直达主界面，以及设置页重新打开指南。
