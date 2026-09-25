---
work_package: WP3
title: "Furkin 1.19.2 command message API migration"
status: complete
recorded_at: "2026-09-23T23:34:41+08:00"
branch: mc1.19.2
head: a6e9f6732b5e9563fd8011a9de73632a4f357af2
minecraft: 1.19.2
forge: 43.2.0
mapping: "official 1.19.2"
compile_errors_before: 65
compile_errors_after: 48
---

# WP3：1.19.2 命令消息 API 迁移记录

## 目标

将 `FurkinCommand` 中 1.20.1 风格的 `sendSuccess(Supplier<Component>, boolean)` 调用迁移为 1.19.2 的 `sendSuccess(Component, boolean)`，且不改变命令回执内容。

## 结论

- WP3 已完成。
- `FurkinCommand.java` 中 17 个 `sendSuccess` lambda 调用已全部移除。
- 1.19.2 编译错误从 **65** 降至 **48**。
- `FurkinCommand.java` 当前不再产生编译错误。
- `sendFailure` 的 43 个调用无需修改。
- WP3 只修改一个 Java 文件。
- 未提交、未推送。

## API 差异

### 1.19.2

通过 1.19.2 official mapped jar 使用 `javap` 得到：

```text
public void sendSuccess(net.minecraft.network.chat.Component, boolean);
public void sendFailure(net.minecraft.network.chat.Component);
```

### 1.20.1

通过 1.20.1 official mapped jar 使用 `javap` 得到：

```text
public void sendSuccess(java.util.function.Supplier<net.minecraft.network.chat.Component>, boolean);
public void sendFailure(net.minecraft.network.chat.Component);
```

因此迁移方向确定为：

```java
// 1.20.1
src.sendSuccess(() -> Component.literal("..."), false);

// 1.19.2
src.sendSuccess(Component.literal("..."), false);
```

### 可复现核对命令

```powershell
& 'C:\Program Files\Java\jdk-17.0.2\bin\javap.exe' `
  -classpath '<1.19.2 mapped forge jar>' `
  net.minecraft.commands.CommandSourceStack |
  Select-String -Pattern 'sendSuccess|sendFailure'
```

```powershell
& 'C:\Program Files\Java\jdk-17.0.2\bin\javap.exe' `
  -classpath '<1.20.1 mapped forge jar>' `
  net.minecraft.commands.CommandSourceStack |
  Select-String -Pattern 'sendSuccess|sendFailure'
```

## 修改范围

修改文件：

```text
src/main/java/com/wanancat/furkin/internal/command/FurkinCommand.java
```

替换操作：

```text
sendSuccess(() ->   ->   sendSuccess(
```

替换数量：17。

未修改：

- `sendFailure` 的 43 个调用。
- 命令参数、权限、返回值。
- 消息文本和组件样式。
- 其他 Java 文件和资源文件。

## 调用点分布

| 方法 | 行号 | 数量 |
|---|---|---:|
| `summon` | 178、179 | 2 |
| `list` | 217、250、262 | 3 |
| `forget` | 308 | 1 |
| `rename` | 334 | 1 |
| `addExp` | 390 | 1 |
| `setMode` | 421 | 1 |
| `unlockSkill` | 463 | 1 |
| `inspect` | 517、521、524 | 3 |
| `pouchAdd` | 575、581、586 | 3 |
| `pouchClear` | 643 | 1 |
| **合计** |  | **17** |

## 行为说明

1.20.1 的 `Supplier<Component>` 会延迟构造消息组件；1.19.2 必须在调用 `sendSuccess` 时直接提供组件。

当前 17 个消息均为简单的字符串拼接、`String.format` 或组件链构造，没有依赖延迟求值的副作用，因此改为立即构造不会改变命令结果。`false` 参数保持不变，继续控制是否向其他管理员广播回执。

## 编译验证

命令：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

.\gradlew.bat compileJava --rerun-tasks --no-daemon `
  --init-script build\compat-javac.init.gradle `
  *> build\wp3-command-compile.log
```

结果：

| 项目 | 结果 |
|---|---|
| Gradle 退出码 | `1` |
| 执行任务 | `:compileJava` |
| 结果 | `BUILD FAILED` |
| WP2 后错误数 | `65` |
| WP3 后错误数 | `48` |
| 本轮减少 | `17` |
| 日志 | `build\wp3-command-compile.log` |
| 日志大小 | `17,384` 字节 |

日志尾部：

```text
48 errors

FAILURE: Build failed with an exception.
> Task :compileJava FAILED
BUILD FAILED in 10s
1 actionable task: 1 executed
```

`FurkinCommand.java` 已不在错误文件列表中。

## 静态复核

已执行：

```text
FurkinCommand.java sendSuccess 总数：17
FurkinCommand.java sendSuccess lambda 数：0
FurkinCommand.java sendFailure 总数：43
git diff --check：通过
```

## 剩余 48 个错误分布

| 文件 | 数量 |
|---|---:|
| `internal/client/FurkinPanelScreen.java` | 20 |
| `internal/client/FurkinRecordScreen.java` | 7 |
| `internal/client/ContractNameScreen.java` | 5 |
| `internal/client/RenameScreen.java` | 5 |
| `internal/client/FurkinStatusIconRenderer.java` | 3 |
| `internal/contract/FurkinCombatMode.java` | 2 |
| `internal/registry/ModCreativeTab.java` | 2 |
| `internal/skill/SkillPassiveDispatcher.java` | 2 |
| `internal/effect/BleedingEffect.java` | 1 |
| `internal/menu/FurkinPouchMenu.java` | 1 |
| **合计** | **48** |

## WP3 验收检查表

- [x] 通过 `javap` 确认 1.19.2 `sendSuccess` 接收 `Component`。
- [x] 确认 1.20.1 `sendSuccess` 接收 `Supplier<Component>`。
- [x] 定位全部 17 个不兼容调用。
- [x] 移除全部 `sendSuccess(() -> ` 包装。
- [x] 保留消息内容、样式和广播布尔值。
- [x] 未修改 `sendFailure`。
- [x] 使用正式 1.19.2 配置重新编译。
- [x] `FurkinCommand` 的 17 个错误全部消除。
- [x] 总错误数降至 48。
- [x] `git diff --check` 通过。

## WP3 交付物

- 本文件：`docs\mc1.19.2-migration\wp3-command-message-api.md`
- 源码修改：`src\main\java\com\wanancat\furkin\internal\command\FurkinCommand.java`
- 编译日志：`build\wp3-command-compile.log`

## 进入 WP4 的注意事项

- 下一组简单 API 差异集中在 Button 和 EditBox：
  - `Button.builder(...)` 不存在，预计 9 处。
  - `EditBox.setHint(...)` 不存在，预计 2 处。
- 这些调用分布在 `ContractNameScreen`、`RenameScreen` 和其他客户端界面中。
- Button/EditBox 修改会进入客户端代码，但仍应保持为独立子步骤，避免与 `GuiGraphics` 重写混合。
- 每个子步骤继续使用 JDK 17 和 1.19.2 正式配置进行编译验证。