---
document: "Furkin Minecraft 1.19.2 migration work breakdown"
project: "furkin"
repository: "https://github.com/wanancat/furkin"
branch: "mc1.19.2"
source_minecraft: "1.20.1"
target_minecraft: "1.19.2"
source_forge: "47.2.0"
target_forge_candidate: "43.2.0"
java: 17
status: "in_progress"
compile_probe_errors: 115
compile_errors_current: 0
updated: "2026-09-24"
---

# Furkin 1.19.2 迁移工作拆分与执行计划

> 本文档是后续执行代理的工作底稿。每个工作包均包含目标、依赖、涉及文件、操作项和验收标准。
> 当前状态：**WP0-WP8 的编译迁移已完成，当前剩余编译错误为 0；WP9a 服务端启动、WP9b 客户端启动和 WP9c 单人世界冒烟测试通过，专用服务端正常关闭、GUI 功能、视觉和多人游戏验收仍在 WP9。**
>
> 最新编译验证（2026-09-24）：`WP8b` 后 **0 个 javac 错误**（日志 `build/wp8b-compile.log`）。WP5f 见 `docs/mc1.19.2-migration/wp5f-panel-screen-api.md`，WP6 见 `docs/mc1.19.2-migration/wp6-scroll-widget-api.md`，WP7 见 `docs/mc1.19.2-migration/wp7-creative-tab-api.md` 和 `docs/mc1.19.2-migration/wp7-status-icon-render-api.md`，WP8 见 `docs/mc1.19.2-migration/wp8-scattered-core-api.md`，WP9a 见 `docs/mc1.19.2-migration/wp9a-server-smoke.md`，WP9b 见 `docs/mc1.19.2-migration/wp9b-client-smoke.md`，WP9c 见 `docs/mc1.19.2-migration/wp9c-client-world-smoke.md`。

## 1. 结论

Furkin 当前面向 Minecraft 1.20.1 / Forge 47.x，**不能直接在 Minecraft 1.19.2 上加载运行**。

强制切换到 1.19.2 进行编译探测后共有 **115 个 javac 错误**。主要问题不是业务架构，而是 1.20.1 与 1.19.2 之间的客户端 GUI、注册表、渲染事件和映射方法差异。

判断：

- 不需要整体重写业务逻辑。
- 需要中等偏大的兼容性移植。
- 先以“编译通过”为第一阶段目标，再进行客户端、服务端和多人游戏验证。
- 预计净工作时间约 **3 至 5 个工作日**；如果要求所有 GUI 和渲染行为都达到 1.20.1 同等质量，预留 **5 至 7 个工作日**。
- 主要不确定性集中在四个大屏及滚动列表、创造模式标签页、头顶图标渲染阶段。

## 2. 当前基线

| 项目 | 当前值 |
|---|---|
| 仓库路径 | `D:\frukin_dev\frukin_1_19_2` |
| 当前分支 | `mc1.19.2` |
| 上游分支 | `origin/mc1.19.2` |
| Minecraft | `1.20.1` |
| Forge | `47.2.0` |
| mappings | `official 1.20.1` |
| Java | 17 |
| `pack_format` | `15` |
| Mixin | 未使用 |
| Java 文件 | 约 87 个 |
| Java 代码量 | 约 12,173 行 |
| 1.19.2 强制编译错误（迁移前） | 115 |
| 当前剩余编译错误 | 0 |
| 编译探测日志 | `build/compat-1.19.2-compile.log` |
| 错误分类日志 | `build/compat-1.19.2-errors.txt` |

已确认的兼容项：

- Java 17 符合 Minecraft 1.19.2 的运行时要求。
- 项目没有 Mixin 配置，不需要处理 Mixin 版本迁移。
- 公共 API 包 `com.wanancat.furkin.api` 可以保留，但迁移后仍需编译和调用方测试确认。

## 2.1 工程执行原则

> 本节为强制原则，后续工作包和代理执行时必须遵守。

- **官方实现优先**：任何手写替代方案落地前，先查看目标版本的官方类、方法签名、构造器和实现字节码，确认是否存在可直接调用或复用的 API。
- **先查参数再造轮子**：优先复用官方默认值、常量、贴图坐标、布局规则和辅助方法，避免凭记忆手写等价逻辑。
- **确无可用能力才自行实现**：若官方确实缺失或语义不兼容，再实现兼容代码，并在工作包文档中记录证据、差异和不采用官方路径的原因。
- **验证证据留痕**：优先使用 `javap`、Mappings、官方源码或运行时探测确认，结果写入对应 WP 文档。

## 3. 错误分布

| 类别 | 数量 | 工作性质 | 风险 |
|---|---:|---|---|
| `GuiGraphics` 不存在 | 23 | 客户端 GUI 从 `GuiGraphics` 移植到 `PoseStack` | 高 |
| `.level()` 不存在 | 33 | 映射方法替换 | 低 |
| `.serverLevel()` 不存在 | 17 | 映射方法替换 | 低 |
| `sendSuccess` 的 Component 用法不兼容 | 17 | 命令消息 API 替换 | 低 |
| `Button.builder(...)` 不存在 | 9 | 控件构造器替换 | 低 |
| `EditBox.setHint(...)` 不存在 | 2 | 改为 `setSuggestion(...)` | 低 |
| `GoalSelector.removeAllGoals(Predicate)` 不存在 | 2 | 目标选择器重写 | 中 |
| `AbstractScrollWidget` 接口变化 | 3 | 滚动容器重写 | 高 |
| 创造模式标签页 API 变化 | 2 | 旧式 `CreativeModeTab` 注册 | 中 |
| `org.joml.Matrix4f` 不存在 | 1 | 改为 `com.mojang.math.Matrix4f` | 中 |
| `RenderLevelStageEvent.Stage.AFTER_ENTITIES` 不存在 | 1 | 选择兼容阶段并实机验证 | 中 |
| `DamageTypeTags.BYPASSES_INVULNERABILITY` 不存在 | 2 | 使用 1.19.2 伤害源判断 | 中 |
| `Slot.setByPlayer(ItemStack)` 不存在 | 1 | 改用旧槽位更新方式 | 低 |
| `Entity.damageSources()` 不存在 | 1 | 改用 1.19.2 `DamageSource` | 低 |
| GUI 覆盖方法签名变化 | 2 | 随滚动容器一起处理 | 高 |

## 4. 工作包总表

| ID | 工作包 | 依赖 | 净工作量 | 交付物 |
|---|---|---:|---:|---|
| WP0 | 基线锁定与验证环境 | 无 | 0.1 天 | 可重复的 1.19.2 编译命令和基线记录 |
| WP1 | 版本元数据与加载链 | WP0 | 0.2 天 | Gradle、`mods.toml`、`pack.mcmeta` 可被 1.19.2 接受 |
| WP2 | 全局映射方法替换 | WP1 | 0.5 天 | `.level()`、`.serverLevel()` 全部消除 |
| WP3 | 命令消息 API | WP1 | 0.2 天 | `FurkinCommand` 适配 1.19.2 `sendSuccess` |
| WP4 | 控件构造与简单 Screen | WP1 | 0.3 天 | Button/EditBox API 编译通过 |
| WP5 | `GuiGraphics` 到 `PoseStack` GUI 移植 | WP4 | 1.0 至 1.5 天 | 四个 Screen 编译并完成基本显示 |
| WP6 | `AbstractScrollWidget` 滚动列表 | WP5 | 0.5 至 1.0 天 | 技能列表可滚动、命中、拖拽和滚轮正常 |
| WP7 | 创造模式标签页与渲染事件 | WP1 | 0.5 至 1.0 天 | 标签页注册成功，头顶图标层级正确 |
| WP8 | 零散核心游戏 API | WP1 | 0.5 天 | 战斗模式、槽位、流血、无敌穿透适配 |
| WP9 | 编译、运行和回归验证 | WP2-WP8 | 1.0 至 1.5 天 | 客户端/服务端/多人游戏验证报告 |
| WP10 | 文档与发布元数据收尾 | WP9 | 0.2 天 | README、CHANGELOG、版本说明同步 |

## 5. 详细任务

### WP0：基线锁定与验证环境

目标：确保迁移工作基于可重复的 1.19.2 编译环境，避免 Gradle 缓存和 Java 版本造成假错误。

操作：

- [ ] 确认当前分支为 `mc1.19.2` 且工作树状态已知。
- [ ] 确认使用 JDK 17：`C:\Program Files\Java\jdk-17.0.2`。
- [ ] 记录迁移前 commit hash。
- [ ] 保留或重新生成 `build/compat-1.19.2-errors.txt`。
- [ ] 每次大批量修改后重新执行 1.19.2 编译，不只在 1.20.1 环境编译。

建议命令：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat --stop
.\gradlew.bat compileJava --no-daemon
```

验收标准：

- [ ] Java 版本明确为 17。
- [ ] Gradle 使用的 Minecraft 和 Forge 版本可追溯。
- [ ] 每次验证的错误日志可被重新读取和分类。

### WP1：版本元数据与加载链

目标：让构建文件、Forge 元数据和资源包元数据指向 1.19.2。

涉及文件：

- `gradle.properties`
- `src/main/resources/META-INF/mods.toml`
- `src/main/resources/pack.mcmeta`

操作：

- [ ] 将 `minecraft_version` 从 `1.20.1` 改为 `1.19.2`。
- [ ] 将 `minecraft_version_range` 改为适合 1.19.2 的范围，例如 `[1.19.2,1.20)`。
- [ ] 将 `forge_version` 改为与 1.19.2 匹配的版本，候选为 `43.2.0`。
- [ ] 将 `forge_version_range` 改为 `[43,)`，或根据实际目标 Forge 版本收窄。
- [ ] 将 `loader_version_range` 改为 `[43,)`，避免 Forge 47 加载器依赖残留。
- [ ] 将 `mapping_version` 改为 `1.19.2`。
- [ ] 将 `mod_version` 从 `1.20.1-...` 改为 `1.19.2-...`。
- [ ] 更新 `mods.toml` 顶部注释中的 `Forge 1.20.1` 描述。
- [ ] 将 `pack.mcmeta` 的 `pack_format` 从 `15` 改为 `9`。

验收标准：

- [ ] Gradle 不再尝试解析 1.20.1 Forge 47 依赖。
- [ ] `mods.toml` 中的 Forge、Minecraft 版本范围允许 1.19.2。
- [ ] `pack_format` 与 1.19.2 资源包格式一致。
- [ ] 重新运行编译后，错误类型只剩源码 API 差异，而不是依赖解析和加载器拒绝。

### WP2：全局映射方法替换

目标：消除旧映射方法缺失造成的 50 个错误。

涉及范围：

- `internal/command`
- `internal/contract`
- `internal/event`
- `internal/effect`
- `internal/growth`
- `internal/inventory`
- `internal/item`
- `internal/network`
- `internal/skill`

操作：

- [ ] 全仓搜索 `.level()`。
- [ ] 对 `Entity`、`LivingEntity`、`ServerPlayer` 等对象，将 `obj.level()` 改为 `obj.getLevel()`。
- [ ] 全仓搜索 `.serverLevel()`。
- [ ] 对 `ServerPlayer` 对象，将 `player.serverLevel()` 改为 `player.getLevel()`。
- [ ] 优先依靠 javac 定位类型不匹配，避免不加判断地全局字符串替换。
- [ ] 替换后再次编译，区分真实的 1.19.2 类型差异和级联错误。

建议搜索命令：

```powershell
rg -n "\.level\(\)|\.serverLevel\(\)" src/main/java
```

验收标准：

- [ ] 不再出现 `method level()` 错误。
- [ ] 不再出现 `method serverLevel()` 错误。
- [ ] 客户端、服务端逻辑端判断没有因替换而改变。

### WP3：命令消息 API

目标：将 1.20.1 的命令反馈 Supplier API 改为 1.19.2 的 Component API。

主要文件：

- `src/main/java/com/wanancat/furkin/internal/command/FurkinCommand.java`

操作：

- [ ] 搜索 `sendSuccess(`。
- [ ] 将 `sendSuccess(() -> Component.literal(...), false)` 改为 `sendSuccess(Component.literal(...), false)`。
- [ ] 同样处理 `Component.translatable(...)` 和其他返回 Component 的调用。
- [ ] 检查 `sendFailure` 是否有相同问题。
- [ ] 不使用全局正则直接删除 lambda，逐处确认表达式类型。

建议搜索命令：

```powershell
rg -n "sendSuccess|sendFailure" src/main/java/com/wanancat/furkin/internal/command
```

验收标准：

- [ ] 不再出现 `Component is not a functional interface`。
- [ ] 命令成功和失败消息内容不变。
- [ ] 命令执行权限和返回值不变。

### WP4：控件构造与简单 Screen

目标：完成不涉及 `GuiGraphics` 重写的控件 API 兼容修改。

涉及文件：

- `ContractNameScreen.java`
- `FurkinPanelScreen.java`
- `FurkinRecordScreen.java`
- `RenameScreen.java`

操作：

- [ ] 将 `EditBox.setHint(...)` 改为 `EditBox.setSuggestion(...)`。
- [ ] 将 9 处 `Button.builder(label, onPress).bounds(x, y, w, h).build()` 改为 1.19.2 构造器形式：
      `new Button(x, y, w, h, label, onPress)`。
- [ ] 确认 `EditBox` 的当前构造器在 1.19.2 中可用，不进行无必要的重写。
- [ ] 保持按钮尺寸、位置、回调和可见性完全一致。
- [ ] 修改控件后配合 WP5 一起编译，避免屏幕方法签名仍然报错。

验收标准：

- [ ] 不再出现 `Button.builder` 和 `setHint` 错误。
- [ ] 输入框提示文本仍可显示。
- [ ] 所有按钮在 1.19.2 GUI 中可按原位置点击。

### WP5：`GuiGraphics` 到 `PoseStack` GUI 移植

目标：将四个 Screen 从 1.20.1 的 `GuiGraphics` 渲染 API 移植到 1.19.2 的 `PoseStack`渲染 API。

主要文件：

- `src/main/java/com/wanancat/furkin/internal/client/ContractNameScreen.java`
- `src/main/java/com/wanancat/furkin/internal/client/FurkinPanelScreen.java`
- `src/main/java/com/wanancat/furkin/internal/client/FurkinRecordScreen.java`
- `src/main/java/com/wanancat/furkin/internal/client/RenameScreen.java`

操作：

- [ ] 删除 `net.minecraft.client.gui.GuiGraphics` 导入。
- [ ] 将 `render` 方法签名改为 1.19.2 的 `PoseStack` 版本。
- [ ] 将 `renderBg` 方法签名改为 1.19.2 的 `PoseStack` 版本。
- [ ] 将 `renderLabels` 方法签名改为 1.19.2 的 `PoseStack` 版本。
- [ ] 将自定义辅助方法参数从 `GuiGraphics` 改为 `PoseStack`。
- [ ] 将 `gui.drawString(...)` 改为 `font.draw(...)` 或该 Screen 在 1.19.2 下的等价调用。
- [ ] 将 `gui.drawCenteredString(...)` 改为 `font.drawShadow` 加宽度计算，或抽取统一辅助方法。
- [ ] 将 `gui.blit(...)` 改为 `blit(...)`/`RenderSystem` 的 1.19.2 渲染方式。
- [ ] 将 `gui.blitNineSliced(...)` 替换为 1.19.2 可用的九宫格绘制实现或自行拆分纹理。
- [ ] 将 `gui.fill(...)` 改为 `fill(...)` 的 1.19.2 版本。
- [ ] 将 `gui.renderTooltip(...)` 改为 `renderTooltip(...)` 的 1.19.2 版本。
- [ ] 保留现有颜色、层级、裁剪和 `PoseStack` 平移顺序。
- [ ] 对 `FurkinPanelScreen` 和 `FurkinRecordScreen` 的列表行渲染单独核对一次。

重点文件规模：

- `FurkinPanelScreen.java` 约 84 KB，是本工作的最大风险文件。
- `FurkinRecordScreen.java` 约 31 KB，包含 `ItemRow` 和 `LineRow` 两个渲染随动点。

验收标准：

- [ ] 所有 `GuiGraphics` 错误消失。
- [ ] 四个 Screen 在 1.19.2 客户端均可打开。
- [ ] 文本、背景、按钮、悬浮提示和物品图标显示位置正确。
- [ ] 调整窗口大小后布局不崩溃、不越界。

### WP6：`AbstractScrollWidget` 滚动列表

目标：将 `FurkinPanelScreen.SkillListWidget` 按 1.19.2 的 `AbstractScrollWidget` 契约重写。

主要文件：

- `src/main/java/com/wanancat/furkin/internal/client/FurkinPanelScreen.java`

需要确认或实现的 1.19.2 方法：

- `getInnerHeight()`
- `scrollbarVisible()`
- `scrollRate()`
- `renderContents(PoseStack, int, int, float)`
- `renderBackground(PoseStack)` 或 1.19.2 实际存在的背景钩子

操作：

- [ ] 使用映射后的 1.19.2 Forge jar，对 `AbstractScrollWidget` 执行 `javap` 确认完整方法签名。
- [ ] 实现 `scrollbarVisible()`。
- [ ] 将 `renderBackground(GuiGraphics)` 改为正确的 1.19.2 覆盖签名；如果不画官方边框，保留空实现。
- [ ] 将 `renderContents(GuiGraphics, ...)` 改为 `renderContents(PoseStack, ...)`。
- [ ] 核对 `scrollAmount()`、`setScrollAmount(...)` 在 1.19.2 中的可见性和 clamp 行为。
- [ ] 保持行命中坐标使用屏幕坐标，内容绘制使用 `PoseStack` 平移后的坐标。
- [ ] 验证列表内容变短时滚动量会重新收敛。
- [ ] 验证鼠标滚轮、拖动滚动条、点击行和按钮互不抢事件。

验收标准：

- [ ] 不再出现 `SkillListWidget is not abstract` 和 GUI 覆盖方法错误。
- [ ] 列表超出高度时出现滚动条。
- [ ] 滚轮滚动步长等于一行高度。
- [ ] 滚动条拖动和鼠标点击命中正确。
- [ ] 技能列表变短后不会保留不可达的滚动偏移。

### WP7：创造模式标签页与渲染事件

目标：适配 1.19.2 的旧式创造模式标签页和可用的世界渲染阶段。

涉及文件：

- `src/main/java/com/wanancat/furkin/internal/registry/ModCreativeTab.java`
- `src/main/java/com/wanancat/furkin/internal/client/FurkinStatusIconRenderer.java`

操作：

- [x] 确认 1.19.2 中 `CreativeModeTab` 的旧式构造器签名；`CreativeModeTab(String)` 会自动加入 `TABS`。
- [x] 确认 `ForgeRegistries.CREATIVE_MODE_TABS` 不适用于 1.19.2，改用物品 `Item.Properties.tab(FURKIN_TAB)`。
- [x] 用官方 `Item.Properties.tab` 同时收录品牌标签页与搜索页，不覆写 `fillItemList`。
- [x] 保持标签页标题、图标和物品顺序不变。
- [x] 将 `org.joml.Matrix4f` 替换为 1.19.2 的 `com.mojang.math.Matrix4f`。
- [x] 将 `RenderLevelStageEvent.Stage.AFTER_ENTITIES` 替换为 1.19.2 的 `AFTER_PARTICLES`。
- [x] 对比阶段顺序，记录 `AFTER_WEATHER` 为后备阶段；实机遮挡关系留到 WP9 验证。
- [x] 保持 Billboarding、头顶偏移、亮度和渲染距离判定不变。

验收标准：

> WP7 已完成编译层迁移；下列运行时项目将在 WP9 客户端实机验证中勾选。

- [ ] 创造模式物品栏中出现 Furkin 标签页。
- [ ] 四个物品均可被搜索和取得。
- [ ] 头顶图标在第三人称、第一人称和远处实体上层级合理。
- [ ] 图标不会明显被地形、粒子和方块错误遮挡。
- [ ] 关闭客户端配置 `showStatusIcon` 后图标消失。

### WP8：零散核心游戏 API

目标：处理无法归入 GUI 或映射替换的零散 1.19.2 逻辑 API。

涉及文件：

- `internal/contract/FurkinCombatMode.java`
- `internal/menu/FurkinPouchMenu.java`
- `internal/effect/BleedingEffect.java`
- `internal/skill/SkillPassiveDispatcher.java`

操作：

- [x] 将 `GoalSelector.removeAllGoals(Predicate)` 改为 `getAvailableGoals().removeIf(...)`。
- [x] 已按 1.20.1 字节码语义静态确认，只删除原先匹配的模组目标类型。
- [x] 将 `Slot.setByPlayer(ItemStack)` 改为等价转调目标 `Slot.set(ItemStack)`。
- [x] 将 `Entity.damageSources().generic()` 改为 1.19.2 官方 `DamageSource.GENERIC`。
- [x] 已静态确认 `DamageSource.GENERIC` 仍不带攻击者且绕过护甲；运行时表现归入 WP9。
- [x] 将 `DamageTypeTags.BYPASSES_INVULNERABILITY` 改为 `DamageSource#isBypassInvul()`。
- [x] 已静态确认该判断与 1.20.1 标签判断等价，不会扩大到普通伤害。

验收标准：

- [x] 四个适配点均使用 1.19.2 官方 API，且 `compileJava` 成功。
- [x] 具体 API 语义、后备方案和证据记录在 `docs/mc1.19.2-migration/wp8-scattered-core-api.md`。
- [ ] 战斗模式切换后的宠物行为、槽位同步、流血与九命猫运行时表现归入 WP9。

### WP9：编译、运行和回归验证

> WP9a 进度（2026-09-24）：服务端已完成启动冒烟测试并进入 `Done`；未发现启动崩溃。Ctrl-C 强制中断导致正常关闭和保存未确认，详见 `docs/mc1.19.2-migration/wp9a-server-smoke.md`。
> WP9b 进度（2026-09-24）：客户端已完成启动冒烟测试并进入主菜单阶段；未发现迁移导致的崩溃或注册失败。既有 `bleeding.png` 缺失纹理已单独记录，详见 `docs/mc1.19.2-migration/wp9b-client-smoke.md`。
> WP9c 进度（2026-09-24）：单人世界创建、进入、移动、暂停、保存和退出通过；集成服务端正常停止并显示 `All dimensions are saved`，Gradle 为 `BUILD SUCCESSFUL`。既有 `bleeding.png` 缺失纹理在日志中出现 3 次，但仍判定为非迁移回归，详见 `docs/mc1.19.2-migration/wp9c-client-world-smoke.md`。

目标：不仅编译通过，还确认 1.19.2 客户端和服务端的实际行为。

操作：

- [ ] 执行 `.\gradlew.bat clean compileJava --no-daemon`。
- [x] 执行 `.\gradlew.bat runClient --no-daemon`。
- [x] 执行 `.\gradlew.bat runServer --no-daemon`。
- [ ] 单人世界测试契约、录、成长、技能树、装备、药水、魂石和复活流程。
- [ ] 测试四个 Screen 的打开、关闭、按钮、输入、滚动和 ESC。
- [ ] 测试创造模式标签页和物品搜索。
- [ ] 测试头顶状态图标在近处、远处、第一人称和第三人称下的显示。
- [ ] 建立专用 Forge 1.19.2 服务端进行连接测试。
- [ ] 测试客户端-服务端数据同步、命令反馈和保存/重载。
- [ ] 检查日志中是否出现注册、映射、网络包和渲染异常。
- [ ] 保存最终编译日志和运行日志。

验收标准：

- [ ] `compileJava` 成功。
- [ ] 客户端可启动并进入世界。
- [ ] 服务端可启动并接受连接。
- [ ] 单人及多人环境核心流程可用。
- [ ] 没有阻断性崩溃、网络包错误或 GUI 操作失效。
- [ ] 已知剩余问题有明确记录和复现步骤。

### WP10：文档与发布元数据收尾

目标：保证源码、文档和发布信息一致。

涉及文件：

- `README.md`
- `README.zh-CN.md`
- `CHANGELOG.md`
- `gradle.properties`
- `src/main/resources/META-INF/mods.toml`
- `src/main/resources/pack.mcmeta`

操作：

- [ ] 删除或更新所有 1.20.1、Forge 47 的旧描述。
- [ ] 添加 1.19.2 兼容版本说明。
- [ ] 更新版本号、构建说明和运行环境。
- [ ] 更新 CHANGELOG 中本次迁移的变更条目。
- [ ] 检查 Javadoc 和注释中的版本引用。
- [ ] 确认发布 jar 的元数据和实际目标版本一致。

验收标准：

- [ ] 文档不再误导用户安装到 1.20.1。
- [ ] 发布元数据与实际依赖一致。
- [ ] 变更记录可以说明迁移范围和兼容性边界。

## 6. 建议执行顺序

```text
WP0
  -> WP1
      -> WP2
      -> WP3
      -> WP4
          -> WP5
              -> WP6
      -> WP7
      -> WP8
          -> 第一轮 compileJava
              -> 修正级联错误
                  -> WP9 客户端/服务端验证
                      -> WP10 文档收尾
```

实际执行时可以并行处理：

- WP2、WP3、WP4 可以在 WP1 后并行。
- WP7 和 WP8 可以在 GUI 迁移期间由另一人或另一轮修改处理。
- WP6 必须建立在 `FurkinPanelScreen` 的 `PoseStack` 改造之上。
- WP9 不应在 WP8 完成前进行最终判定。

## 7. 风险登记

| 风险 | 概率 | 影响 | 缓解措施 |
|---|---|---|---|
| `PoseStack` GUI 移植后视觉层级错误 | 高 | 高 | 每改一个 Screen 就实机检查，不最后集中调试 |
| `AbstractScrollWidget` 事件行为差异 | 高 | 高 | 先 `javap` 确认签名，再单独测试滚轮、拖动、点击 |
| 头顶图标阶段选择错误 | 中 | 中 | 对比多个阶段并实机验证遮挡关系 |
| 创造模式标签页注册失败 | 中 | 中 | 先查 1.19.2 源码和映射 jar，再最小化注册测试 |
| 无敌穿透伤害判断扩大 | 中 | 高 | 使用目标版本原版伤害逻辑，增加伤害回归测试 |
| 编译错误掩盖运行时问题 | 高 | 高 | 编译通过后必须执行客户端、服务端和多人测试 |
| GUI 中文/英文文本布局差异 | 中 | 低 | 在中文和英文资源下分别检查宽度和换行 |

## 8. 验收里程碑

### M1：配置可加载

- [ ] WP1 完成。
- [ ] Gradle 使用 1.19.2 / Forge 43.x 解析成功。
- [ ] `mods.toml` 和 `pack.mcmeta` 元数据正确。

### M2：编译通过

- [x] WP2 至 WP8 完成。
- [x] `compileJava` 无错误。
- [x] 115 个探测错误全部消除或有明确记录。

### M3：可启动

- [x] `runClient` 可进入主菜单和世界。
- [x] `runServer` 可启动。
- [ ] 无注册、网络和启动阶段崩溃。

### M4：功能可用

- [ ] 契约、成长、技能、装备、背包和复活流程可用。
- [ ] 四个 Screen 全部可用。
- [ ] 创造模式标签页可用。
- [ ] 头顶状态图标可用。

### M5：可发布

- [ ] 客户端/服务端/多人测试通过。
- [ ] README、CHANGELOG 和版本号更新。
- [ ] 构建产物可被 1.19.2 Forge 43.x 正常加载。

## 9. 最终工作量和交付判断

建议按以下口径对外表达：

- 最小目标：只要求 1.19.2 编译通过，预计 **2 至 3 个工作日**。
- 正常目标：客户端和服务端基本可玩，预计 **3 至 5 个工作日**。
- 质量目标：GUI、滚动、渲染和多人同步全部达到原版水平，预计 **5 至 7 个工作日**。

工作量主要集中在 GUI 改造，而不是业务逻辑重写。若后续出现 1.19.2 与 1.20.1 在实体同步、网络包序列化或渲染管线上的新差异，应将其加入 WP8 或 WP9，而不是继续扩大单个工作包。
