---
work_package: WP2
title: "Furkin 1.19.2 mapping method migration"
status: complete
recorded_at: "2026-09-23T23:30:06+08:00"
branch: mc1.19.2
head: a6e9f6732b5e9563fd8011a9de73632a4f357af2
minecraft: 1.19.2
forge: 43.2.0
mapping: "official 1.19.2"
compile_errors_before: 115
compile_errors_after: 65
---

# WP2：1.19.2 映射方法迁移记录

## 目标

处理 1.20.1 与 1.19.2 之间最机械、风险最低的实体世界访问映射差异：

- 消除 `.serverLevel()` 调用。
- 消除 `.level()` 调用。
- 处理 `ServerPlayer#getLevel()` 在 1.19.2 中的协变返回类型差异。
- 在每一步后通过真实 1.19.2 / Forge 43.2.0 编译确认净收益。

本工作包不处理 GUI、命令消息、滚动列表、创造模式标签页或渲染事件。

## 结论

- WP2 已完成。
- `.serverLevel()` 在 `src/main/java` 中剩余 **0 处**。
- `.level()` 在 `src/main/java` 中剩余 **0 处**。
- 编译错误从 **115** 降至 **65**。
- 收敛过程为：`115 -> 98 -> 73 -> 65`。
- WP2 实际修改 13 个 Java 文件。
- 未提交、未推送，供后续工作包继续叠加。

## 版本与方法背景

| 项目 | 值 |
|---|---|
| 分支 | `mc1.19.2` |
| HEAD | `a6e9f6732b5e9563fd8011a9de73632a4f357af2` |
| Minecraft | `1.19.2` |
| Forge | `43.2.0` |
| Mappings | `official 1.19.2` |
| Java | `17.0.2` |

1.19.2 official mappings 中，实体世界访问使用 `getLevel()`。该项迁移不能只依赖无差别全局替换，因为 `.level()`、`.serverLevel()` 这类文本还可能在非实体 API 中出现，且 `ServerPlayer` 与普通 `LivingEntity` 的返回类型不同。

## 替换盘点

### `.serverLevel()` 基线

迁移前共发现：

```text
.serverLevel() 17 处
```

涉及 5 个文件：

- `internal/command/FurkinCommand.java`
- `internal/contract/FurkinRecordActionHandler.java`
- `internal/item/FurkinRecordItem.java`
- `internal/network/ConfirmContractPacket.java`
- `internal/skill/SkillProgress.java`

这些接收者均为服务器玩家或服务器侧上下文，替换为 `.getLevel()` 后仍能得到 `ServerLevel`。

### `.level()` 基线

迁移前共发现：

```text
.level() 33 个调用，分布在 32 行
```

其中 `CommonEvents.java:219` 一行包含两个调用。

涉及 9 个文件：

| 文件 | 调用数 |
|---|---:|
| `internal/effect/BleedingEffect.java` | 1 |
| `internal/inventory/PouchDrop.java` | 2 |
| `internal/contract/FurkinCompanionManager.java` | 6 |
| `internal/skill/SkillPassiveDispatcher.java` | 11 |
| `internal/contract/FurkinRecordActionHandler.java` | 3 |
| `internal/contract/FurkinCombatModeHandler.java` | 1 |
| `internal/contract/FurkinContractHandler.java` | 3 |
| `internal/growth/FurkinGrowth.java` | 2 |
| `internal/event/CommonEvents.java` | 4 |

## 执行步骤

| 阶段 | 操作 | 错误数 | 净变化 | 日志 |
|---|---|---:|---:|---|
| WP1 完成基线 | 版本元数据切换后 | 115 | - | `build/wp1-1.19.2-compile.log` |
| WP2a | 17 个 `.serverLevel()` 改为 `.getLevel()` | 98 | -17 | `build/wp2a-serverlevel-compile.log` |
| WP2b | 33 个 `.level()` 改为 `.getLevel()` | 73 | -25 | `build/wp2b-level-compile.log` |
| WP2c | 修正 8 处 `ServerPlayer#getLevel()` 冗余模式匹配 | 65 | -8 | `build/wp2c-pattern-compile.log` |

### WP2a：`.serverLevel()` 替换

修改后复核：

```text
.serverLevel() 剩余 0 处
```

17 个原始错误全部消除，错误数从 115 降至 98。

### WP2b：`.level()` 替换

修改后复核：

```text
.level() 剩余 0 处
```

这个阶段没有直接减少 33 个错误，而是从 98 降至 73。

原因是 1.19.2 中 `ServerPlayer#getLevel()` 已声明为返回 `ServerLevel`。因此以下模式匹配在编译时属于“模式变量类型与表达式类型相同”的冗余判断：

```java
if (!(player.getLevel() instanceof ServerLevel serverLevel)) {
    return Result.NOT_FOUND;
}
```

该形式产生 8 个新增错误。普通 `LivingEntity#getLevel()` 仍返回 `Level`，其对应的 `instanceof ServerLevel` 判断是有效的，不能在全部调用点机械删除。

### WP2c：协变返回类型修正

8 处问题位于：

| 文件 | 数量 |
|---|---:|
| `internal/contract/FurkinCombatModeHandler.java` | 1 |
| `internal/contract/FurkinCompanionManager.java` | 4 |
| `internal/contract/FurkinRecordActionHandler.java` | 3 |

修正方式：

```java
ServerLevel serverLevel = player.getLevel();
```

对于 `ServerPlayer`，该判断在语义上不可能失败，因为服务器玩家只能存在于服务器世界。对普通 `LivingEntity` 的判断仍保留，例如：

```java
if (!(target.getLevel() instanceof ServerLevel serverLevel)) {
    return false;
}
```

修正后，错误数从 73 降至 65。

## 修改文件

WP2 累计涉及以下 13 个唯一 Java 文件：

```text
src/main/java/com/wanancat/furkin/internal/command/FurkinCommand.java
src/main/java/com/wanancat/furkin/internal/contract/FurkinCombatModeHandler.java
src/main/java/com/wanancat/furkin/internal/contract/FurkinCompanionManager.java
src/main/java/com/wanancat/furkin/internal/contract/FurkinContractHandler.java
src/main/java/com/wanancat/furkin/internal/contract/FurkinRecordActionHandler.java
src/main/java/com/wanancat/furkin/internal/effect/BleedingEffect.java
src/main/java/com/wanancat/furkin/internal/event/CommonEvents.java
src/main/java/com/wanancat/furkin/internal/growth/FurkinGrowth.java
src/main/java/com/wanancat/furkin/internal/inventory/PouchDrop.java
src/main/java/com/wanancat/furkin/internal/item/FurkinRecordItem.java
src/main/java/com/wanancat/furkin/internal/network/ConfirmContractPacket.java
src/main/java/com/wanancat/furkin/internal/skill/SkillPassiveDispatcher.java
src/main/java/com/wanancat/furkin/internal/skill/SkillProgress.java
```

## 编译验证命令

每次验证均显式使用 JDK 17：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

.\gradlew.bat compileJava --rerun-tasks --no-daemon `
  --init-script build\compat-javac.init.gradle `
  *> build\wp2c-pattern-compile.log
```

说明：

- 使用正式 `gradle.properties`，不再使用命令行 `-P` 覆盖版本。
- 使用 `--rerun-tasks` 强制重新编译。
- 未执行 `clean`，已有日志得以保留。
- Gradle 需要写入 `C:\Users\wanancat\.gradle`，因此在沙箱环境中需要外部执行权限。

## 最终编译结果

| 项目 | 结果 |
|---|---|
| Gradle 退出码 | `1` |
| 执行任务 | `:compileJava` |
| 结果 | `BUILD FAILED` |
| 错误数 | `65` |
| WP0 基线 | `115` |
| 累计减少 | `50` |
| 最终日志 | `build\wp2c-pattern-compile.log` |
| 日志大小 | `23,763` 字节 |

日志尾部关键内容：

```text
65 errors
> Task :compileJava FAILED
BUILD FAILED in 11s
1 actionable task: 1 executed
```

## 剩余 65 个错误分布

| 文件 | 数量 |
|---|---:|
| `internal/client/FurkinPanelScreen.java` | 20 |
| `internal/command/FurkinCommand.java` | 17 |
| `internal/client/FurkinRecordScreen.java` | 7 |
| `internal/client/ContractNameScreen.java` | 5 |
| `internal/client/RenameScreen.java` | 5 |
| `internal/client/FurkinStatusIconRenderer.java` | 3 |
| `internal/contract/FurkinCombatMode.java` | 2 |
| `internal/registry/ModCreativeTab.java` | 2 |
| `internal/skill/SkillPassiveDispatcher.java` | 2 |
| `internal/effect/BleedingEffect.java` | 1 |
| `internal/menu/FurkinPouchMenu.java` | 1 |
| **合计** | **65** |

后续类别与总计划一致，主要包括 `GuiGraphics`、命令消息 `Component`、Button/EditBox、滚动列表、创造模式标签页、渲染阶段和零散核心 API。

## 最终静态复核

已执行：

```text
.serverLevel() 剩余匹配：0
.level() 剩余匹配：0
player.getLevel() instanceof ServerLevel 剩余匹配：0
git diff --check：通过
```

保留的 `target.getLevel() instanceof ServerLevel` 均针对静态类型为 `LivingEntity` 的接收者。

## WP2 验收检查表

- [x] 盘点 `.serverLevel()` 和 `.level()` 的全部调用。
- [x] 按接收者类型审查，未进行无差别全局替换。
- [x] 清除全部 `.serverLevel()`。
- [x] 清除全部 `.level()`。
- [x] 处理 `ServerPlayer#getLevel()` 的 8 处协变返回类型差异。
- [x] 保留 `LivingEntity` 所需的 `instanceof ServerLevel` 判断。
- [x] 每个阶段重新执行 1.19.2 编译。
- [x] 错误数从 115 收敛至 65。
- [x] `git diff --check` 通过。
- [ ] 全部源码编译通过，属于 WP3-WP8。
- [ ] 客户端、服务端和多人游戏运行验证，属于 WP9。

## WP2 交付物

- 本文件：`docs\mc1.19.2-migration\wp2-mapping-methods.md`
- WP2a 日志：`build\wp2a-serverlevel-compile.log`
- WP2b 日志：`build\wp2b-level-compile.log`
- WP2c 日志：`build\wp2c-pattern-compile.log`
- 13 个 Java 文件的映射兼容修改

## 进入 WP3 的注意事项

- 下一组低风险错误是 `FurkinCommand` 中 17 个命令消息 `Component` 用法。
- 当前剩余错误中，`FurkinCommand` 独占 17 个，适合作为独立 WP3。
- WP3 不应混入 GUI 和渲染修改。
- 每个子步骤必须继续使用 JDK 17 和 1.19.2 正式配置验证。