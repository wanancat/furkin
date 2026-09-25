---
work_package: WP5f
title: "FurkinPanelScreen 1.19.2 GUI migration"
status: complete
recorded_at: "2026-09-24T00:07:55+08:00"
branch: mc1.19.2
head: a6e9f6732b5e9563fd8011a9de73632a4f357af2
minecraft: 1.19.2
forge: 43.2.0
mapping: "official 1.19.2"
compile_errors_before: 28
compile_errors_after: 16
---

# WP5f：`FurkinPanelScreen` 1.19.2 GUI 迁移记录

## 目标

将 `FurkinPanelScreen` 从 1.20.1 的 `GuiGraphics` 渲染链迁移到 1.19.2 的 `PoseStack` 渲染链，同时保持面板、页签、技能列表和按钮的显示行为不变。

本工作包只处理 `FurkinPanelScreen` 中不涉及 1.19.2 `AbstractScrollWidget` 覆写接口的部分。滚动列表剩余改动属于 WP6。

## 结论

- WP5f 已完成。
- 1.19.2 编译错误从 **28** 降至 **16**。
- `FurkinPanelScreen` 原有 17 个错误降至 5 个；剩余 5 个全部属于 WP6。
- 共修改 1 个 Java 文件，新增 2 个私有静态绘制 helper。
- 静态复核通过：`git diff --check` 无输出。
- 未提交、未推送。

## 官方 API 调查

迁移前先通过 1.19.2 official mapped jar 和 `javap` 核对官方 API，没有自行猜测替代接口。

| 1.20.1 写法 | 1.19.2 官方可用能力 | 处理方式 |
|---|---|---|
| `GuiGraphics#blit(ResourceLocation, ...)` | `GuiComponent.blit(PoseStack, ...)`，调用前用 `RenderSystem.setShaderTexture(0, texture)` 绑定贴图 | 新增 `blit(...)` helper |
| `GuiGraphics#drawString(...)` | `Font#draw(PoseStack, ...)` | 改为 `this.font.draw(pose, ...)` |
| `GuiGraphics#drawCenteredString(...)` | `GuiComponent.drawCenteredString(PoseStack, ...)` | 直接调用官方静态方法 |
| `GuiGraphics#fill(...)` | `GuiComponent.fill(PoseStack, ...)` | 直接调用官方静态方法 |
| `GuiGraphics#renderTooltip(...)` | `Screen#renderTooltip(PoseStack, ...)` | 改为 `this.renderTooltip(pose, ...)` |
| `GuiGraphics#blitNineSliced(...)` | 1.19.2 没有对应 `GuiGraphics` API；官方 `AbstractWidget#renderButton` 使用左右两段 blit | 新增 `blitButton(...)` helper，照抄官方两段画法 |

### 按钮底图

1.19.2 官方 `AbstractWidget#renderButton` 的字节码实现是：

```java
this.blit(pose, this.x, this.y,
        0, 46 + state * 20, this.width / 2, this.height);
this.blit(pose, this.x + this.width / 2, this.y,
        BUTTON_TEX_WIDTH - this.width / 2, 46 + state * 20,
        this.width / 2, this.height);
```

因此没有为 22×14 的技能按钮另造九宫格算法，而是直接复用官方按钮贴图、官方切分规则和现有三态 `v` 值：

```java
private static void blitButton(PoseStack pose, int x, int y,
                               int width, int height, int v) {
    RenderSystem.setShaderTexture(0, AbstractWidget.WIDGETS_LOCATION);
    GuiComponent.blit(pose, x, y, 0, v, width / 2, height, 256, 256);
    GuiComponent.blit(pose, x + width / 2, y,
            BUTTON_TEX_WIDTH - width / 2, v,
            width / 2, height, 256, 256);
}
```

`GuiComponent.blit(...)` 自身处理 blend 和 shader 设置；`256, 256` 与 1.20.1 默认贴图尺寸路径一致。

## 实际改动

### 方法签名

11 个方法参数由 `GuiGraphics gui` 改为 `PoseStack pose`。

### 调用点

| 原调用 | 数量 | 迁移结果 |
|---|---:|---|
| `gui.blit(PANEL_TEXTURE, ...)` | 7 | `blit(pose, PANEL_TEXTURE, ...)` |
| `gui.drawString(..., false)` | 8 | `this.font.draw(pose, ...)` |
| `gui.drawCenteredString(...)` | 2 | `GuiComponent.drawCenteredString(pose, ...)` |
| `gui.fill(...)` | 1 | `GuiComponent.fill(pose, ...)` |
| `gui.renderTooltip(...)` / `renderTooltip(gui, ...)` | 4 | `this.renderTooltip(pose, ...)` |
| `gui.blitNineSliced(...)` | 1 | `blitButton(pose, ...)` |

### 常量

- 删除 `BUTTON_SLICE_X`。
- 删除 `BUTTON_SLICE_Y`。
- 删除 `BUTTON_TEX_HEIGHT`。
- 保留 `BUTTON_TEX_WIDTH = 200`，供官方两段按钮画法使用。

## 静态复核

检查结果：

```text
git diff --check：通过
补丁统计：95 insertions, 75 deletions
```

新增 helper：

- `blit(...)`：第 703 行。
- `blitButton(...)`：第 716 行。

`blitNetBoard(PoseStack ...)` 的 Javadoc 和方法结构保持完整。

## 编译验证

编译命令：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat compileJava --rerun-tasks --no-daemon `
  --init-script build\compat-javac.init.gradle `
  *> build\wp5f-panel-screen-compile.log
```

结果：

| 项目 | 结果 |
|---|---|
| Gradle 退出码 | `1` |
| 结果 | `BUILD FAILED` |
| WP5f 前错误数 | `28` |
| WP5f 后错误数 | `16` |
| 本轮减少 | `12` |
| 日志 | `build\wp5f-panel-screen-compile.log` |

编译失败属于本阶段预期结果，剩余错误来自后续 WP6-WP8。

## WP5f 后错误分布

| 文件 | 数量 | 后续工作包 |
|---|---:|---|
| `FurkinPanelScreen.java` | 5 | WP6 |
| `FurkinStatusIconRenderer.java` | 3 | WP7 |
| `FurkinCombatMode.java` | 2 | WP8 |
| `ModCreativeTab.java` | 2 | WP7 |
| `SkillPassiveDispatcher.java` | 2 | WP8 |
| `BleedingEffect.java` | 1 | WP8 |
| `FurkinPouchMenu.java` | 1 | WP8 |
| **合计** | **16** |  |

`FurkinPanelScreen` 剩余的 5 个错误全部是 1.19.2 `AbstractScrollWidget` 接口差异：

- 缺少 `scrollbarVisible()` 实现。
- `renderBackground(PoseStack)` 在 1.19.2 是 private，不能覆写。
- 1.19.2 接口方法名为 `updateNarration`，不是 `updateWidgetNarration`。
- `renderContents` 的渲染上下文仍需从 `GuiGraphics` 改为 `PoseStack`。

## 工程原则

> 做模组时，造轮子前先确认官方实现、官方 API 和可调用参数；确认没有合适能力后再自行实现。

执行要求：

- 先查目标版本 official mapped jar、`javap`、Mappings 或官方源码。
- 优先复用官方构造器、方法、默认值、常量和绘制规则。
- 官方能力缺失或语义不兼容时，才实现兼容代码。
- 自定义替代必须记录依据、差异和验证结果，避免重复造轮子。

## WP5f 验收检查表

- [x] 通过 `javap` 确认 1.19.2 GUI 和控件签名。
- [x] 确认 1.19.2 没有 `GuiGraphics` 类。
- [x] 确认官方 `Font`、`GuiComponent` 和 `Screen` 的可用替代方法。
- [x] 确认 `blitNineSliced` 在 1.19.2 没有直接对应 API。
- [x] 复用官方 `AbstractWidget#renderButton` 两段按钮画法。
- [x] `GuiGraphics` 可编译残留全部收敛到 WP6 范围。
- [x] `git diff --check` 通过。
- [x] 使用 1.19.2 配置完成编译验证。
- [x] 总错误数降至 16。
- [ ] `AbstractScrollWidget` 迁移，属于 WP6。
- [ ] 客户端实机显示验证，属于 WP9。