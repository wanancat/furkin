---
work_package: WP9c
title: "Furkin 1.19.2 client single-player world smoke test"
status: passed_with_known_issue
recorded_at: "2026-09-24T00:58:00+08:00"
branch: mc1.19.2
head: a6e9f6732b5e9563fd8011a9de73632a4f357af2
minecraft: 1.19.2
forge: 43.2.0
java: 17.0.2
client_log: "build/wp9c-client-world.log"
world: "run/saves/新的世界"
result: "world entry, movement, pause, save and exit passed; pre-existing missing texture warning"
---

# WP9c：1.19.2 客户端单人世界冒烟测试

## 测试范围

本工作包验证 Furkin 迁移后的 1.19.2 客户端能否创建并进入单人世界，完成基本移动、暂停、保存和退出。

本阶段不验证：

- 契约、成长、技能树、装备、药水、魂石和复活业务。
- 四个 Screen 的布局、按钮、输入和滚动。
- 创造模式标签页和物品搜索。
- 头顶状态图标在不同视角下的渲染。
- 专用服务端连接和多人同步。

## 运行环境

| 项目 | 值 |
|---|---|
| 仓库 | `D:\frukin_dev\frukin_1_19_2` |
| 分支 | `mc1.19.2` |
| 基线提交 | `a6e9f6732b5e9563fd8011a9de73632a4f357af2` |
| Minecraft | `1.19.2` |
| Forge | `43.2.0` |
| Java | `17.0.2` |
| 客户端日志 | `build/wp9c-client-world.log` |
| 测试世界 | `run/saves/新的世界` |

## 已验证结果

| 检查项 | 结果 | 证据 |
|---|---|---|
| 创建并进入单人世界 | 通过 | `Starting integrated minecraft server version 1.19.2` |
| 客户端连接集成服务端 | 通过 | `Connected to a modded server.` |
| Furkin 网络通道协商 | 通过 | `Channel 'furkin:main' ... ACCEPTED` |
| Furkin 技能加载 | 通过 | `Furkin loaded 13 skills.` |
| 玩家登录世界 | 通过 | `Dev[local:...] logged in with entity id 255` |
| 人物移动、跳跃 | 通过 | 用户实机确认正常 |
| 暂停菜单 | 通过 | 用户实机确认正常 |
| 世界运行异常 | 未发现 | 进入世界后无迁移相关异常 |
| 崩溃报告 | 未生成 | `run/crash-reports` 下无新增报告 |

## 保存与退出

日志确认集成服务端和客户端完成正常关闭：

```text
Stopping singleplayer server as player logged out
Stopping server
Saving players
Saving worlds
ThreadedAnvilChunkStorage: All dimensions are saved
Stopping!
BUILD SUCCESSFUL in 3m 5s
```

结果：

- 世界保存成功。
- 测试世界 `run/saves/新的世界` 已生成。
- 退出码为 `0`。
- 没有 Java 进程残留。

## 已知问题：流血效果纹理缺失

WP9c 日志中记录了 3 次：

```text
[00:52:54] [Worker-Main-8/ERROR] [minecraft/TextureAtlas]: Using missing texture, file furkin:textures/mob_effect/bleeding.png not found
[00:53:14] [Worker-Main-3/ERROR] [minecraft/TextureAtlas]: Using missing texture, file furkin:textures/mob_effect/bleeding.png not found
[00:53:24] [Worker-Main-6/ERROR] [minecraft/TextureAtlas]: Using missing texture, file furkin:textures/mob_effect/bleeding.png not found
```

判断：

- 该提示发生在纹理图集创建或重载阶段。
- Minecraft 会使用缺失纹理占位图，因此不会阻止世界加载。
- `origin/main` 中同样不存在该 PNG。
- 结论：属于原始版本的既有资源缺陷，不是 1.19.2 迁移回归。

后续处理原则：

1. 先检查 1.19.2 官方 `MobEffect` 图标纹理的获取方式。
2. 确认是应补充合法 PNG，还是移除无效纹理引用。
3. 不在迁移分支中随意生成同名占位文件。
4. 若修复，单独记录资源改动和视觉验证结果。

## 当前结论

- WP9c 单人世界冒烟测试：**通过**。
- 基本移动、暂停、保存和退出均正常。
- 未发现由 1.19.2 迁移导致的业务或注册崩溃。
- `bleeding.png` 缺失继续作为既有问题跟踪，不影响本轮结论。
- 专用服务端正常停止和多人连接仍待后续验证。

## 下一步

1. WP9d：逐个检查四个 Screen、按钮、输入、滚动和 ESC。
2. WP9e：检查创造模式标签页和头顶状态图标渲染。
3. WP9f：验证契约、成长、技能、装备、药水、魂石和复活流程。
4. 补做专用服务端正常 `stop`、保存和二次启动验证。