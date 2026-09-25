---
work_package: WP9a
title: "Furkin 1.19.2 dedicated server startup smoke test"
status: partial
recorded_at: "2026-09-24T00:47:53+08:00"
branch: mc1.19.2
head: a6e9f6732b5e9563fd8011a9de73632a4f357af2
minecraft: 1.19.2
forge: 43.2.0
java: 17.0.2
eula: true
startup_log: "build/wp9a1-server.log"
runtime_log: "run/logs/latest.log"
result: "startup passed; graceful shutdown not confirmed"
---

# WP9a：1.19.2 专用服务端启动冒烟测试

## 测试范围

本工作包只验证 Furkin 迁移后的 1.19.2 专用服务端能否完成模组发现、注册、世界加载并进入可接受命令的状态。

本阶段不验证：

- 客户端主菜单和世界进入。
- GUI、滚动列表、创造模式标签页和头顶图标。
- 客户端与服务端连接。
- 契约、成长、技能、装备和复活流程。
- 正常关闭、保存和重载。

## 运行环境

| 项目 | 值 |
|---|---|
| 仓库 | `D:\frukin_dev\frukin_1_19_2` |
| 分支 | `mc1.19.2` |
| 基线提交 | `a6e9f6732b5e9563fd8011a9de73632a4f357af2` |
| Minecraft | `1.19.2` |
| Forge | `43.2.0` |
| Java | `17.0.2` |
| EULA | `run/eula.txt` 中为 `true` |
| 启动日志 | `build/wp9a1-server.log` |
| 服务端日志 | `run/logs/latest.log` |

## 已验证结果

| 检查项 | 结果 | 证据 |
|---|---|---|
| Forge 服务端启动 | 通过 | 日志中出现 `Forge mod loading, version 43.2.0` |
| Furkin 模组发现 | 通过 | 日志中出现 `Furkin builtin species registered.` |
| Furkin 技能注册 | 通过 | 日志中出现 `Furkin loaded 13 skills.` |
| 服务端进入运行态 | 通过 | `Done (20.422s)! For help, type "help"` |
| Gametest 命名空间 | 通过 | `Enabled Gametest Namespaces: [furkin]` |
| Forge Permission API | 通过 | `Successfully initialized permission handler forge:default_handler` |
| 启动阶段致命异常 | 未发现 | 启动后已进入 `Done`，未发现模组加载崩溃 |
| Java 进程残留 | 无 | 审计时无 `java`/`javaw` 进程 |
| JVM 崩溃文件 | 未发现 | `run` 目录下无 `hs_err_pid*.log` |

## 关闭审计

服务端启动完成后通过 Ctrl-C 中断了 Gradle 运行会话。日志最后一条业务记录为：

```text
[00:37:12] [Server thread/INFO] [ne.mi.se.pe.PermissionAPI/]: Successfully initialized permission handler forge:default_handler
```

日志中没有出现：

```text
Stopping server
Saving worlds
All dimensions are saved
BUILD SUCCESSFUL
```

结论：

- Ctrl-C 没有形成可审计的正常关闭流程。
- Gradle 退出码为 `1`，但现有证据更像运行会话被中断，而不是模组崩溃。
- 正常关闭、世界保存和重载仍待 WP9a 后续验证。
- 在确认正常关闭前，不应把服务端验收标记为完整通过。

## 警告分级

| 日志 | 判断 | 处理 |
|---|---|---|
| `Failed to load properties from file: server.properties` | 首次运行或配置生成阶段的非致命错误 | 服务端随后成功启动，后续检查生成的配置 |
| Forge 配置键缺失并补全默认值 | 首次运行的配置迁移行为 | 保留生成配置，后续比较第二次启动 |
| Forge `43.2.0` 低于 `1.19.2` 推荐版 `43.5.0` | 版本建议警告 | 当前锁定候选版本不变，最终兼容测试前再评估 |
| Netty/OSHI 的 JDK 模块及 Windows 计数警告 | JVM 或系统环境警告 | 不影响进入 `Done`，暂不处理 |
| Forge 语言提供 jar 缺少 `mods.toml` | Forge 内部模块的预期警告 | 暂不处理 |

## 当前结论

- WP9a 服务端启动冒烟测试：**通过**。
- WP9a 正常关闭与保存验证：**未确认**。
- 未发现由 Furkin 迁移导致的启动崩溃。
- 下一步应进行客户端启动测试，并补做可正常发送 `stop` 的服务端关闭验证。

## 下一步

1. WP9b：执行 `runClient`，检查主菜单、世界进入、GUI 和渲染阶段异常。
2. WP9a-followup：使用可直接接收 `stop` 命令的服务端控制台，验证保存、停止和二次启动。
3. WP9c：进行客户端与服务端连接、命令反馈和核心流程回归。