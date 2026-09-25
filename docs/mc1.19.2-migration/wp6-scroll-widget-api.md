---
work_package: WP6
title: "Furkin 1.19.2 AbstractScrollWidget migration"
status: complete
recorded_at: "2026-09-24T00:15:32+08:00"
updated_at: "2026-09-24T03:24:17+08:00"
branch: mc1.19.2
head: a6e9f6732b5e9563fd8011a9de73632a4f357af2
minecraft: 1.19.2
forge: 43.2.0
mapping: "official 1.19.2"
compile_errors_before: 16
compile_errors_after: 11
compile_reduction: 5
compile_log: "build/wp6-scroll-widget-compile.log"
selected_option: "方案2：覆写 renderButton 去除背景"
fallback_option: "方案1：沿用官方背景"
---

# WP6：1.19.2 `AbstractScrollWidget` 迁移记录

## 结果

- WP6 已完成。
- 1.19.2 编译错误从 **16** 降至 **11**。
- `FurkinPanelScreen.java` 的 5 个 WP6 错误全部消除，当前为 **0**。
- 方案1（沿用官方背景与边框）曾编译通过；D6-01 实机确认黑底与自定义面板视觉冲突后，已切换为方案2。
- 方案2已启用；方案1保留为后备方案。
- 未提交、未推送。

## 官方 API 调查

1.19.2 与 1.20.1 的关键差异：

| 能力 | 1.19.2 | 1.20.1 |
|---|---|---|
| `scrollbarVisible()` | `protected abstract`，子类必须实现 | 有默认实现 |
| `renderContents(...)` | `PoseStack, int, int, float` | `GuiGraphics, int, int, float` |
| `renderBackground(...)` | `private` | `protected` |
| 念白入口 | `public void updateNarration(...)` | final `updateNarration` + protected `updateWidgetNarration` |
| `renderButton(...)` | 公开可覆写，但固定调用私有 `renderBackground` | 公开可覆写 |

1.19.2 官方 `renderButton` 流程：

```text
检查 visible
→ renderBackground（private，固定调用）
→ enableScissor
→ 按 scrollAmount 平移 PoseStack
→ renderContents
→ popPose / disableScissor
→ renderDecorations（官方滚动条）
```

官方私有背景的实际绘制内容：

- 整个控件区域先填充 `0xFFA0A0A0` 灰色边框色；
- 聚焦时边框改用 `0xFFFFFFFF` 白色；
- 内部区域填充 `0xFF000000` 黑色。

## 方案1：沿用官方背景（后备方案）

原选择原因：

- 代码最少，删除 1.20.1 的空 `renderBackground` 覆写即可。
- 尽可能复用 1.19.2 官方实现，不复制 `renderButton` 的内部流程。
- 接受官方背景与边框的视觉效果，作为当前编译迁移方案。
- 实机验收时若确认它与自定义面板冲突，再启用方案2。

本方案修改：

```java
@Override
protected boolean scrollbarVisible() {
    return getInnerHeight() > getHeight();
}

@Override
public void updateNarration(NarrationElementOutput narration) {
    this.defaultButtonNarrationText(narration);
}

@Override
protected void renderContents(PoseStack pose, int mouseX, int mouseY, float partialTick) {
    // ...
    renderSkillRow(pose, i, hoveredRow == i, buttonHit(mouseX, mouseY, i));
}
```

同时删除 1.20.1 的：

```java
@Override
protected void renderBackground(GuiGraphics gui) {
}
```

1.19.2 不允许覆写或调用这个私有方法，因此方案1会保留官方黑色背景和灰/白边框。

## 方案2：覆写 renderButton 去除背景（当前选择）

D6-01 实机已确认官方黑色背景与自定义面板视觉冲突，因此启用本方案。此方案通过覆写公开的 `renderButton`，复用官方裁剪、滚动位移、内容绘制和滚动条绘制，只省略私有 `renderBackground`：

```java
@Override
public void renderButton(PoseStack pose, int mouseX, int mouseY, float partialTick) {
    if (!this.visible) {
        return;
    }

    enableScissor(
            this.x + 1,
            this.y + 1,
            this.x + this.width - 1,
            this.y + this.height - 1);

    pose.pushPose();
    pose.translate(0.0D, -scrollAmount(), 0.0D);
    renderContents(pose, mouseX, mouseY, partialTick);
    pose.popPose();

    disableScissor();
    renderDecorations(pose);
}
```

方案2没有重写滚动逻辑：

- `scrollAmount()`、`setScrollAmount(...)`、`getMaxScrollAmount()` 继续使用官方实现。
- 滚轮、拖拽和滚动条继续使用 `AbstractScrollWidget`。
- 只复制官方 `renderButton` 中约 20 行的渲染流程，以满足“不调用私有背景方法”的视觉要求。

代价：

- 与官方 `renderButton` 存在一小段实现重复。
- 若后续再迁移到 1.20.1，应删除此覆写，恢复 `renderBackground` 空实现。

## 编译验证

执行命令：

```powershell
C:\Program Files\Java\jdk1.8.0_321 = 'C:\Program Files\Java\jdk-17.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat compileJava --rerun-tasks --no-daemon `
  --init-script build\compat-javac.init.gradle `
  *> build\wp6-scroll-widget-compile.log
```

结果：

| 项目 | 结果 |
|---|---|
| Gradle 退出码 | `1` |
| WP6 前错误数 | `16` |
| WP6 后错误数 | `11` |
| 本轮减少 | `5` |
| `FurkinPanelScreen.java` 错误 | `0` |
| 日志 | `build\wp6-scroll-widget-compile.log` |

`git diff --check` 通过。

编译仍失败属于预期结果，剩余错误归属 WP7-WP8。

### 方案2启用后的重新编译

执行命令：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat compileJava --rerun-tasks --console=plain
```

结果：

| 项目 | 结果 |
|---|---|
| Java | `17.0.2` |
| Gradle 退出码 | `0` |
| `compileJava` | `BUILD SUCCESSFUL in 12s` |

`git diff --check` 通过。方案2已通过重新编译，下一步需重启客户端验证黑底移除，并回归滚动条、滚轮、拖拽和技能按钮命中。

WP9 仍需实机检查列表背景、滚动条、滚轮、拖拽和技能按钮命中。