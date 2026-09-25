---
work_package: WP4
title: "Furkin 1.19.2 Button and EditBox API migration"
status: complete
recorded_at: "2026-09-23T23:42:19+08:00"
branch: mc1.19.2
head: a6e9f6732b5e9563fd8011a9de73632a4f357af2
minecraft: 1.19.2
forge: 43.2.0
mapping: "official 1.19.2"
compile_errors_before: 48
compile_errors_after: 37
---

# WP4：1.19.2 Button 与 EditBox API 迁移记录

## 目标

处理 1.20.1 与 1.19.2 之间客户端简单控件的构造 API 差异：

- 移除 1.19.2 不存在的 `Button.builder(...)`。
- 将 1.20.1 的 `EditBox.setHint(Component)` 等价迁移为 1.19.2 的 `EditBox.setSuggestion(String)`。
- 保持按钮位置、尺寸、文案和点击行为不变。
- 不使用无差别全局替换，逐处核对接收者和参数。

本工作包不处理 `GuiGraphics`、滚动列表或其他渲染 API。

## 结论

- WP4 已完成。
- 共替换 11 处：
  - `Button.builder(...)` 9 处。
  - `EditBox.setHint(...)` 2 处。
- 1.19.2 编译错误从 **48** 降至 **37**。
- WP4 修改 4 个客户端 Java 文件。
- 替换后 `Button.builder(` 和 `.setHint(` 在源码中均为 **0 处**。
- 未提交、未推送。

## API 差异

### Button

通过 1.19.2 official mapped jar 使用 `javap` 得到，1.19.2 `Button` 没有静态 `builder()`，公开构造器包括：

```text
Button(int x, int y, int width, int height, Component message, Button.OnPress onPress)
Button(int x, int y, int width, int height, Component message, Button.OnPress onPress, Button.OnTooltip onTooltip)
```

1.20.1 支持构建器：

```java
Button.builder(label, onPress)
        .bounds(x, y, width, height)
        .build();
```

迁移方式：

```java
new Button(x, y, width, height, label, onPress);
```

### EditBox

1.20.1 同时提供：

```text
setSuggestion(String)
setHint(Component)
```

1.19.2 提供：

```text
setSuggestion(String)
```

1.19.2 没有 `setHint(Component)`。`setSuggestion` 会在输入值为空时绘制灰色占位文本，可作为可见提示文本的 1.19.2 等价实现。

迁移方式：

```java
// 1.20.1
input.setHint(Component.translatable(key));

// 1.19.2
input.setSuggestion(Component.translatable(key).getString());
```

注意：1.19.2 `EditBox` 构造器中的 `Component` 参数是 narration message，不是可见 hint，因此不能直接删除原 `setHint` 调用。

## 替换清单

### Button.builder

| 文件 | 原始行 | 替换 |
|---|---:|---|
| `ContractNameScreen.java` | 54 | 确认按钮改为 `new Button(...)` |
| `ContractNameScreen.java` | 61 | 取消按钮改为 `new Button(...)` |
| `RenameScreen.java` | 62 | 确认按钮改为 `new Button(...)` |
| `RenameScreen.java` | 68 | 取消按钮改为 `new Button(...)` |
| `FurkinRecordScreen.java` | 173 | 关闭按钮改为 `new Button(...)` |
| `FurkinRecordScreen.java` | 338 | `detailButton` 改为直接构造 |
| `FurkinPanelScreen.java` | 434 | 页签按钮改为 `new Button(...)` |
| `FurkinPanelScreen.java` | 442 | 洗点按钮改为 `new Button(...)` |
| `FurkinPanelScreen.java` | 452 | 战斗模式按钮改为 `new Button(...)` |
| **合计** |  | **9** |

### EditBox.setHint

| 文件 | 原始行 | 迁移 |
|---|---:|---|
| `ContractNameScreen.java` | 50 | `setHint(Component.translatable(...))` 改为 `setSuggestion(...getString())` |
| `RenameScreen.java` | 55 | `setHint(Component.translatable(...))` 改为 `setSuggestion(...getString())` |
| **合计** |  | **2** |

## 修改文件

```text
src/main/java/com/wanancat/furkin/internal/client/ContractNameScreen.java
src/main/java/com/wanancat/furkin/internal/client/RenameScreen.java
src/main/java/com/wanancat/furkin/internal/client/FurkinRecordScreen.java
src/main/java/com/wanancat/furkin/internal/client/FurkinPanelScreen.java
```

未修改：

- 按钮坐标、宽高和布局。
- 按钮文案与点击回调。
- 输入框最大长度、默认值和 narration 参数。
- 其他 Java、资源和构建文件。

## 编译验证

执行命令：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

.\gradlew.bat compileJava --rerun-tasks --no-daemon `
  --init-script build\compat-javac.init.gradle `
  *> build\wp4-button-editbox-compile.log
```

结果：

| 项目 | 结果 |
|---|---|
| Gradle 退出码 | `1` |
| 执行任务 | `:compileJava` |
| 结果 | `BUILD FAILED` |
| WP3 后错误数 | `48` |
| WP4 后错误数 | `37` |
| 本轮减少 | `11` |
| 日志 | `build\wp4-button-editbox-compile.log` |
| 日志大小 | `13,190` 字节 |

日志尾部关键内容：

```text
37 errors

> Task :compileJava FAILED

FAILURE: Build failed with an exception.
BUILD FAILED in 12s
1 actionable task: 1 executed
```

编译仍失败属于预期结果，剩余错误来自后续 GUI、渲染、注册表和核心 API 差异。

## 静态复核

WP4b 替换完成后已执行：

```text
Button.builder( 剩余匹配：0
.setHint(      剩余匹配：0
git diff --check：通过
```

WP4c 编译日志统计：

```text
ERROR_COUNT=37
```

## 剩余 37 个错误分布

| 文件 | 数量 | 后续工作包 |
|---|---:|---|
| `internal/client/FurkinPanelScreen.java` | 17 | WP5 / WP6 |
| `internal/client/FurkinRecordScreen.java` | 5 | WP5 / WP6 |
| `internal/client/FurkinStatusIconRenderer.java` | 3 | WP7 |
| `internal/client/ContractNameScreen.java` | 2 | WP5 |
| `internal/contract/FurkinCombatMode.java` | 2 | WP8 |
| `internal/registry/ModCreativeTab.java` | 2 | WP7 |
| `internal/client/RenameScreen.java` | 2 | WP5 |
| `internal/skill/SkillPassiveDispatcher.java` | 2 | WP8 |
| `internal/effect/BleedingEffect.java` | 1 | WP8 |
| `internal/menu/FurkinPouchMenu.java` | 1 | WP8 |
| **合计** | **37** |  |

## WP4 验收检查表

- [x] 通过 `javap` 确认 1.19.2 `Button` 没有 `builder()`。
- [x] 确认 1.19.2 `Button` 公开构造器签名。
- [x] 通过 `javap` 和字节码确认 `EditBox.setSuggestion(String)` 的占位文本行为。
- [x] 定位全部 9 处 `Button.builder(...)`。
- [x] 定位全部 2 处 `EditBox.setHint(...)`。
- [x] 保持按钮坐标、尺寸、文案和点击逻辑。
- [x] 保留输入框 narration 构造参数。
- [x] 替换后残留搜索均为 0。
- [x] 使用正式 1.19.2 配置重新编译。
- [x] 总错误数降至 37。
- [x] `git diff --check` 通过。
- [ ] `GuiGraphics` 和滚动列表迁移，属于 WP5/WP6。
- [ ] 客户端、服务端和多人游戏运行验证，属于 WP9。

## WP4 交付物

- 本文件：`docs\mc1.19.2-migration\wp4-button-editbox-api.md`
- 4 个客户端 Java 文件的兼容修改
- 编译日志：`build\wp4-button-editbox-compile.log`

## 进入 WP5 的注意事项

- 四个 Screen 当前仍余 26 个错误：
  - `FurkinPanelScreen.java`：17。
  - `FurkinRecordScreen.java`：5。
  - `ContractNameScreen.java`：2。
  - `RenameScreen.java`：2。
- 下一阶段应继续拆分为“先盘点、后批量替换、再编译验证”的子步骤。
- `FurkinPanelScreen` 同时包含 GUI 绘制和滚动列表风险，不应与简单 Screen 的大范围重写混在一起。
- 每个子步骤继续使用 JDK 17 和正式 1.19.2 配置验证。
