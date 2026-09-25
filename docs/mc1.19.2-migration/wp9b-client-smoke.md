---
work_package: WP9b
title: "Furkin 1.19.2 client startup smoke test"
status: passed_with_known_issue
recorded_at: "2026-09-24T00:50:48+08:00"
branch: mc1.19.2
head: a6e9f6732b5e9563fd8011a9de73632a4f357af2
minecraft: 1.19.2
forge: 43.2.0
java: 17.0.2
client_log: "build/wp9b-client.log"
runtime_log: "run/logs/latest.log"
result: "client startup passed; pre-existing missing texture warning"
---

# WP9b：1.19.2 客户端启动冒烟测试

## 测试范围

本工作包验证 Furkin 迁移后的 1.19.2 客户端能否完成 Forge 加载、模组加载、资源加载并进入主菜单。

本阶段不验证：

- 进入单人世界或连接专用服务端。
- 四个 Screen 的布局、按钮、输入和滚动。
- 创造模式标签页和物品搜索。
- 头顶状态图标。
- 契约、成长、技能、装备和复活流程。

## 运行环境

| 项目 | 值 |
|---|---|
| 仓库 | `D:\frukin_dev\frukin_1_19_2` |
| 分支 | `mc1.19.2` |
| 基线提交 | `a6e9f6732b5e9563fd8011a9de73632a4f357af2` |
| Minecraft | `1.19.2` |
| Forge | `43.2.0` |
| Java | `17.0.2` |
| 客户端日志 | `build/wp9b-client.log` |
| 运行日志 | `run/logs/latest.log` |

## 已验证结果

| 检查项 | 结果 | 证据 |
|---|---|---|
| Forge 客户端启动 | 通过 | `forgeclientuserdev` 成功启动 |
| Furkin 模组加载 | 通过 | `Furkin builtin species registered.` |
| 客户端资源索引 | 通过 | 方块、粒子、生物效果等 atlas 已创建 |
| 声音引擎 | 通过 | `Sound engine started` |
| 主菜单启动阶段 | 通过 | 客户端持续运行并保持响应 |
| 客户端崩溃报告 | 未生成 | `run/crash-reports` 下未新增报告 |
| 测试进程清理 | 完成 | 测试结束后无 `java`/`javaw` 残留 |

## 已知问题

### 缺失流血效果纹理

日志：

```text
[Worker-Main-4/ERROR] [minecraft/TextureAtlas]: Using missing texture, file furkin:textures/mob_effect/bleeding.png not found
```

只读核对结果：

- `origin/main` 中同样不存在 `src/main/resources/assets/furkin/textures/mob_effect/bleeding.png`。
- 因此该问题属于原始版本已有的资源缺失，不是 1.19.2 迁移引入的回归。
- 当前不会阻止客户端启动，但流血效果图标会使用 Minecraft 缺失纹理占位图。

后续处理：

1. 单独确认 1.20.1 原版运行时是否也出现同一提示。
2. 如果确认是既有缺陷，在资源修复工作包中补图或移除无效纹理引用。
3. 不在 WP9 中临时伪造该纹理。

## 警告分级

| 日志 | 判断 | 处理 |
|---|---|---|
| Forge `43.2.0` 低于 `1.19.2` 推荐版 `43.5.0` | 版本建议警告 | 当前候选版本不变，发布前评估升级 |
| Netty/OSHI 的 JDK 模块及 Windows 计数警告 | JVM 或系统环境警告 | 不影响客户端进入主菜单 |
| `minecraft:item.goat_horn.play` 等原版声音缺失 | Minecraft 1.19.2 原版资源警告 | 非 Furkin 问题 |
| `rendertype_entity_translucent_emissive` sampler 警告 | 原版/Forge 渲染警告 | 暂不影响启动 |
| Realms 登录失败 | 开发环境未登录 Realms | 非本次迁移问题 |
| `libpng iCCP sRGB` 警告 | 资源 PNG 元数据警告 | 非阻断问题，后续视觉验收观察 |

## 当前结论

- WP9b 客户端启动冒烟测试：**通过**。
- 未发现由 1.19.2 迁移导致的启动崩溃、注册失败或模组加载失败。
- 唯一明确的 Furkin 错误是既有缺失纹理，已记录为非迁移回归。
- GUI 布局、滚动交互、创造标签页和头顶渲染仍需后续实机功能验收。

## 下一步

1. WP9c：进入单人世界，验证基础加载、命令和核心业务流程。
2. WP9d：逐个检查四个 Screen、滚动、按钮、输入和 ESC。
3. WP9e：检查创造模式标签页和头顶状态图标渲染。
4. 单独补做专用服务端正常 `stop`、保存和二次启动验证。