---
work_package: WP0
title: "Furkin 1.19.2 compile baseline"
status: complete
recorded_at: "2026-09-23T23:06:34+08:00"
repository: "https://github.com/wanancat/furkin"
branch: mc1.19.2
head: a6e9f6732b5e9563fd8011a9de73632a4f357af2
source_minecraft: 1.20.1
source_forge: 47.2.0
probe_minecraft: 1.19.2
probe_forge: 43.2.0
compile_probe_errors: 115
---

# WP0：1.19.2 编译基线记录

## 目标

在开始任何源码迁移前，锁定当前仓库状态，并确认可使用显式 JDK 17 和 1.19.2 参数重复执行编译探测。

## 结论

- 当前项目不能直接在 Minecraft 1.19.2 上编译。
- 使用 1.19.2 / Forge 43.2.0 执行 `compileJava`，得到 **115 个源码编译错误**。
- 失败发生在 `:compileJava` 阶段，不是 Gradle 依赖解析阶段。
- 当前源码和版本配置尚未迁移，`gradle.properties` 仍指向 Minecraft 1.20.1 / Forge 47.2.0。
- WP0 已完成，下一步可以进入 WP1。

## Git 基线

| 项目 | 值 |
|---|---|
| 仓库路径 | `D:\frukin_dev\frukin_1_19_2` |
| 当前分支 | `mc1.19.2` |
| 上游分支 | `origin/mc1.19.2` |
| HEAD commit | `a6e9f6732b5e9563fd8011a9de73632a4f357af2` |
| Fetch 地址 | `https://github.com/wanancat/furkin` |
| Push 地址 | `git@github.com:wanancat/furkin.git` |
| `main` 当前提交 | `a6e9f6732b5e9563fd8011a9de73632a4f357af2` |
| 分支是否领先上游 | 否 |

WP0 检查时，工作区唯一未跟踪文件为：

```text
?? docs/mc1.19.2-migration/mc1.19.2-migration-work-breakdown.md
```

编译探测后再次检查：

```text
## mc1.19.2...origin/mc1.19.2
?? docs/mc1.19.2-migration/mc1.19.2-migration-work-breakdown.md
```

没有已跟踪源码或构建配置变更。

## Java 环境

### 全局环境

当前全局环境不是 JDK 17：

| 项目 | 值 |
|---|---|
| `JAVA_HOME` | `C:\Program Files\Java\jdk1.8.0_321` |
| `java` | `1.8.0_321` |
| `javac` | `1.8.0_321` |

因此不能直接依赖当前 shell 中的 `java` 和 `javac` 执行 Forge 1.19.2 构建。

### 显式 JDK 17

可用的 JDK 17 路径：

```text
C:\Program Files\Java\jdk-17.0.2
```

已直接验证：

```text
java version "17.0.2" 2022-01-18 LTS
javac 17.0.2
```

后续 Gradle 命令必须在同一进程中临时设置：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

## 源码版本基线

执行 WP0 时，`gradle.properties` 仍保持原始 1.20.1 配置：

```properties
minecraft_version=1.20.1
forge_version=47.2.0
mapping_version=1.20.1
mod_version=1.20.1-0.0.1.0
```

编译探测通过命令行参数临时覆盖这些值，没有修改该文件。

## 可复现编译命令

以下命令已成功将构建推进到 `:compileJava`，并复现 115 个源码错误：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

.\gradlew.bat compileJava --no-daemon `
  '-Pminecraft_version=1.19.2' `
  '-Pforge_version=43.2.0' `
  '-Pmapping_version=1.19.2' `
  '-Pmod_version=1.19.2-wp0' `
  --init-script build\compat-javac.init.gradle `
  *> build\wp0-1.19.2-compile.log
```

注意：Windows PowerShell 调用 `gradlew.bat` 时，`-P` 参数必须使用显式引号。未加引号时曾错误解析为：

```text
net.minecraftforge:forge:1-43:userdev3
```

显式引号后正确解析为：

```text
Minecraft 1.19.2 + Forge 43.2.0
```

## 编译结果

| 项目 | 结果 |
|---|---|
| Gradle wrapper | `8.1.1` |
| 构建 Java | `17.0.2` |
| 探测 Minecraft | `1.19.2` |
| 探测 Forge | `43.2.0` |
| 执行任务 | `:compileJava` |
| 编译结果 | `BUILD FAILED` |
| 源码错误数 | `115` |
| 新日志路径 | `build\wp0-1.19.2-compile.log` |
| 新日志大小 | `41,911` 字节 |
| 原探测日志 | `build\compat-1.19.2-compile.log` |
| 原错误分类文件 | `build\compat-1.19.2-errors.txt` |

日志尾部关键内容：

```text
Note: Some messages have been simplified; recompile with -Xdiags:verbose to get full output
115 errors
> Task :compileJava FAILED
BUILD FAILED in 9s
```

日志中的中文错误文本可能显示为乱码，这是终端编码问题；错误数量、文件路径、行号和英文 API 名称仍然可读。

## 工作区检查结论

编译探测只写入了 `build\` 下的日志，没有修改：

- `gradle.properties`
- `build.gradle`
- `settings.gradle`
- `src\main\java`
- `src\main\resources`
- `mods.toml`
- `pack.mcmeta`

`git diff --stat` 在 WP0 检查后为空。

## WP0 验收检查表

- [x] 确认当前分支和上游关联。
- [x] 记录 HEAD commit。
- [x] 确认全局 Java 版本不是 JDK 17。
- [x] 确认显式 JDK 17 可用。
- [x] 确认临时 1.19.2 参数可以被 Gradle 正确解析。
- [x] 复现 `:compileJava` 失败。
- [x] 记录 115 个源码错误。
- [x] 确认编译探测未修改源码和正式版本配置。
- [x] 生成可复现命令和基线记录。

## WP0 交付物

- 本文件：`docs\mc1.19.2-migration\wp0-baseline.md`
- 编译日志：`build\wp0-1.19.2-compile.log`
- 原错误分类：`build\compat-1.19.2-errors.txt`
- 迁移计划：`docs\mc1.19.2-migration\mc1.19.2-migration-work-breakdown.md`

## 进入 WP1 的注意事项

- 必须先修改版本元数据，再执行真实迁移编译，避免继续依赖命令行临时覆盖。
- `mods.toml`、`gradle.properties` 和 `pack.mcmeta` 需要同步修改。
- 后续 Gradle 命令仍须显式使用 JDK 17。
- WP1 完成后，应重新生成错误基线并与本次 115 个错误做差异比较。
