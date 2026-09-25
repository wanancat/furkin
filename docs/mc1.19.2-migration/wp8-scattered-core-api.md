---
document: "WP8 1.19.2 scattered core API migration"
project: "furkin"
branch: "mc1.19.2"
target_minecraft: "1.19.2"
target_forge: "43.2.0"
java: 17
updated: "2026-09-24"
status: "compile_complete_runtime_validation_pending"
---

# WP8：零散核心游戏 API 迁移记录

## 1. 结果

WP8b 已完成四个零散 API 的 1.19.2 适配：

- `GoalSelector` 的目标删除方式
- `Slot` 的槽位设置方法
- 流血效果的通用伤害源
- 九命猫对无敌穿透伤害的判断

验证结果：

```text
.\gradlew.bat compileJava --rerun-tasks --no-daemon
BUILD SUCCESSFUL
```

- 编译前剩余错误：6
- 编译后剩余错误：0
- 编译日志：`build/wp8b-compile.log`
- `git diff --check`：通过

运行时行为仍需在 WP9 的客户端、服务端和多人测试中确认；本文只把可静态证明的 API 语义迁移记为完成。

## 2. GoalSelector：按类型删除目标

### 原 1.20.1 写法

```java
animal.targetSelector.removeAllGoals(goal ->
        goal instanceof HurtByTargetGoal
                || goal instanceof OwnerHurtByTargetGoal
                || goal instanceof OwnerHurtTargetGoal
                || goal instanceof NearestAttackableTargetGoal);
```

### 1.19.2 官方可用入口

1.19.2 的 `GoalSelector` 没有谓词版 `removeAllGoals(Predicate)`，公开方法只有：

```text
public void removeAllGoals();
public void removeGoal(Goal);
public Set<WrappedGoal> getAvailableGoals();
```

`getAvailableGoals()` 直接返回内部可修改的 `Set<WrappedGoal>`。因此采用：

```java
animal.targetSelector.getAvailableGoals().removeIf(wrapped ->
        wrapped.getGoal() instanceof HurtByTargetGoal
                || wrapped.getGoal() instanceof OwnerHurtByTargetGoal
                || wrapped.getGoal() instanceof OwnerHurtTargetGoal
                || wrapped.getGoal() instanceof NearestAttackableTargetGoal);
```

行动目标同理：

```java
animal.goalSelector.getAvailableGoals().removeIf(wrapped ->
        wrapped.getGoal() instanceof MeleeAttackGoal);
```

### 为什么不是 `removeGoal(Goal)`

`javap -c` 显示 1.20.1 的 `removeAllGoals(Predicate)` 实际是：

```text
availableGoals.removeIf(wrapped -> predicate.test(wrapped.getGoal()))
```

没有停止运行中目标的额外步骤。

1.19.2 的 `removeGoal(Goal)` 则会先停止匹配的正在运行目标，再删除，语义更重。改用它会改变原实现行为，所以没有采用。

### 为什么不调用无参 `removeAllGoals()`

无参版本会清空整个选择器，随后还需要重挂原版和其他模组的目标；遗漏、重复或顺序改变都会造成 AI 行为回归，因此没有采用。

### 后备方案

如果后续实机发现 `getAvailableGoals().removeIf(...)` 对某个模组目标产生兼容问题，后备方案是保存本模组新增目标的实例引用，并只对这些实例调用 `removeGoal(goal)`。该方案需要核对 `stop()` 带来的行为差异，不作为首选。

## 3. Slot：槽位设置

1.20.1 的 `Slot#setByPlayer(ItemStack)` 只是：

```text
setByPlayer(stack)
    -> set(stack)
```

因此 1.19.2 直接使用 `Slot#set(ItemStack)`：

```java
slot.set(ItemStack.EMPTY);
```

`Slot#set(...)` 内部执行 `Container#setItem(...)` 后调用 `setChanged()`，与 1.20.1 `setByPlayer` 的最终行为一致。

后备方案是不调用 `Slot` 方法，手写 `container.setItem(index, stack)` 和 `slot.setChanged()`。该写法重复了官方实现，故不采用。

## 4. 流血效果：通用伤害源

原写法：

```java
entity.hurt(entity.damageSources().generic(), damage);
```

1.19.2 没有 `Entity#damageSources()`，官方通用常量是：

```java
entity.hurt(DamageSource.GENERIC, damage);
```

已核对 1.20.1 的 `minecraft:generic` 伤害类型位于 `bypasses_armor` 标签，1.19.2 的 `DamageSource.GENERIC` 也是绕过护甲的通用无攻击者伤害源。两者对本模组关心的属性一致：

- 没有攻击者归属
- 不会触发战斗经验登记
- 不会触发攻击者反弹
- 绕过护甲

后备方案是创建自定义无攻击者 `DamageSource`。只有确认某个 Forge 环境中的 `DamageSource.GENERIC` 行为不再符合需求时才使用，否则属于重复实现官方常量。

## 5. 九命猫：无敌穿透判断

原写法：

```java
if (event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
```

1.19.2 没有 `DamageTypeTags` 数据包标签体系，`DamageSource` 公开提供：

```java
public boolean isBypassInvul();
```

迁移后：

```java
if (event.getSource().isBypassInvul()) {
```

这是 1.19.2 原版自身使用的同类判断，不会把普通伤害误判为无敌穿透。

后备方案是额外按 `msgId` 白名单判断 `outOfWorld`、`genericKill` 等来源。该方案覆盖自定义伤害源的能力较差，而且会复制原版标志位逻辑，仅在实机确认模组伤害源未设置 `bypassInvul` 时启用。

## 6. 修改文件

- `src/main/java/com/wanancat/furkin/internal/contract/FurkinCombatMode.java`
- `src/main/java/com/wanancat/furkin/internal/menu/FurkinPouchMenu.java`
- `src/main/java/com/wanancat/furkin/internal/effect/BleedingEffect.java`
- `src/main/java/com/wanancat/furkin/internal/skill/SkillPassiveDispatcher.java`

## 7. 待 WP9 验证

- 四档战斗模式切换后，宠物目标和近战行为符合预期。
- 背包与装备槽位移动、Shift 点击和同步正常。
- 流血伤害不产生攻击者归属、战斗经验或反弹。
- 带无敌穿透标记的伤害不会被九命猫救回，普通致命伤可以正常触发。
