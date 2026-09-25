---
work_package: WP1
title: "Furkin 1.19.2 version metadata migration"
status: complete
recorded_at: "2026-09-23T23:15:16+08:00"
branch: mc1.19.2
head: a6e9f6732b5e9563fd8011a9de73632a4f357af2
minecraft: 1.19.2
forge: 43.2.0
mapping: "official 1.19.2"
pack_format: 9
compile_errors: 115
---

# WP1：1.19.2 版本元数据迁移记录

## 目标

将项目的正式构建元数据从 Minecraft 1.20.1 / Forge 47.2.0 切换到 Minecraft 1.19.2 / Forge 43.2.0，并确保 Forge 模组元数据和资源包格式一致。

## 结论

- WP1 已完成。
- 项目正式配置现在指向 Minecraft 1.19.2、Forge 43.2.0 和 official 1.19.2 mappings。
- `mods.toml` 的目标版本注释已更新。
- `pack.mcmeta` 的 `pack_format` 已从 15 改为 9。
- 使用正式配置执行 `:compileJava`，仍得到 **115 个源码编译错误**。
- 该数量与 WP0 的 1.19.2 基线完全一致，说明版本元数据迁移本身没有引入额外错误。
- 下一步可以进入 WP2，开始处理源码映射方法兼容性。

## 基线信息

| 项目 | 值 |
|---|---|
| 分支 | `mc1.19.2` |
| HEAD | `a6e9f6732b5e9563fd8011a9de73632a4f357af2` |
| 原始 Minecraft | `1.20.1` |
| 原始 Forge | `47.2.0` |
| 目标 Minecraft | `1.19.2` |
| 目标 Forge | `43.2.0` |
| mappings | `official 1.19.2` |
| Java | `17.0.2` |

## 修改文件

本次 WP1 只修改以下三个文件：

```text
gradle.properties
src/main/resources/META-INF/mods.toml
src/main/resources/pack.mcmeta
```

Git diff 统计：

```text
gradle.properties                     | 14 +++++++-------
src/main/resources/META-INF/mods.toml |  2 +-
src/main/resources/pack.mcmeta        |  2 +-
3 files changed, 9 insertions(+), 9 deletions(-)
```

## gradle.properties

修改后的关键配置：

```properties
minecraft_version=1.19.2
minecraft_version_range=[1.19.2,1.19.3)
forge_version=43.2.0
forge_version_range=[43,)
loader_version_range=[43,)
mapping_channel=official
mapping_version=1.19.2
mod_version=1.19.2-0.0.1.0
```

变更说明：

- `minecraft_version` 改为 1.19.2。
- `minecraft_version_range` 改为只接受 1.19.2。
- `forge_version` 改为 43.2.0。
- `forge_version_range` 改为 43.x 及以上。
- `loader_version_range` 改为 43.x 及以上。
- `mapping_version` 改为 1.19.2。
- `mod_version` 改为 `1.19.2-0.0.1.0`。

## mods.toml

修改：

```diff
-# furkin mod metadata -- Forge 1.20.1
+# furkin mod metadata -- Forge 1.19.2
```

Forge 和 Minecraft 依赖版本范围仍由 `gradle.properties` 中的占位符注入：

```toml
loaderVersion="${loader_version_range}"
versionRange="${forge_version_range}"
versionRange="${minecraft_version_range}"
```

## pack.mcmeta

修改：

```diff
-    "pack_format": 15
+    "pack_format": 9
```

其他 JSON 字段保持不变。

## 编译验证

验证命令：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

.\gradlew.bat compileJava --rerun-tasks --no-daemon `
  --init-script build\compat-javac.init.gradle `
  *> build\wp1-1.19.2-compile.log
```

该命令：

- 没有使用临时 `-P` 版本覆盖。
- 使用修改后的正式 `gradle.properties`。
- 使用显式 JDK 17。
- 使用 `--rerun-tasks` 避免 Gradle 跳过编译。
- 没有执行 `clean`，因此 WP0 日志仍然保留。

## 编译结果

| 项目 | 结果 |
|---|---|
| Gradle wrapper | `8.1.1` |
| Java | `17.0.2` |
| Minecraft | `1.19.2` |
| Forge | `43.2.0` |
| 执行任务 | `:compileJava` |
| 结果 | `BUILD FAILED` |
| 错误数 | `115` |
| WP0 基线 | `115` |
| 数量差异 | `0` |
| 日志路径 | `build\wp1-1.19.2-compile.log` |
| 日志大小 | `41,912` 字节 |

日志尾部关键内容：

```text
115 errors
> Task :compileJava FAILED
BUILD FAILED in 10s
1 actionable task: 1 executed
```

日志中的中文错误文本可能显示为乱码，这是控制台编码问题；错误数量、文件路径、行号和英文 API 名称仍然可读。

## 工作区最终状态

```text
## mc1.19.2...origin/mc1.19.2
 M gradle.properties
 M src/main/resources/META-INF/mods.toml
 M src/main/resources/pack.mcmeta
?? docs/mc1.19.2-migration/mc1.19.2-migration-work-breakdown.md
?? docs/mc1.19.2-migration/wp0-baseline.md
```

本次编译没有修改 Java 源码或资源内容。

## WP1 验收检查表

- [x] Minecraft 版本切换到 1.19.2。
- [x] Minecraft 版本范围限制到 1.19.2。
- [x] Forge 版本切换到 43.2.0。
- [x] Forge 加载器和依赖范围切换到 43.x。
- [x] mappings 切换到 official 1.19.2。
- [x] 模组版本改为 1.19.2 前缀。
- [x] `mods.toml` 注释同步。
- [x] `pack.mcmeta` 格式改为 9。
- [x] Gradle 使用正式配置进入 `:compileJava`。
- [x] WP1 错误数与 WP0 基线一致。
- [x] 未发现意外源码或资源变更。

## WP1 交付物

- 本文件：`docs\mc1.19.2-migration\wp1-version-metadata.md`
- 正式配置修改：
  - `gradle.properties`
  - `src\main\resources\META-INF\mods.toml`
  - `src\main\resources\pack.mcmeta`
- WP1 编译日志：`build\wp1-1.19.2-compile.log`
- WP0 基线记录：`docs\mc1.19.2-migration\wp0-baseline.md`

## 进入 WP2 的注意事项

- WP2 应先处理机械性强、风险低的映射方法：
  - `.level()` 改为 `.getLevel()`
  - `.serverLevel()` 改为 `.getLevel()`
- 替换前应逐处确认接收者类型，避免错误地全局字符串替换。
- 每次批量替换后都必须使用 JDK 17 重新运行 `compileJava`。
- WP2 不应在同一个提交中混入 GUI、创造模式标签页或渲染事件的高风险改动。
