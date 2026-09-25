# WP-05：技能退款、schema 校验与热重载一致性

- 工作项：M-02 + M-04
- 实施日期：2026-09-25
- 基线：`e0f845e`（`fix: 建立网络协议版本边界`）
- 目标版本：`1.19.2-0.0.2.0`
- 状态：代码、静态构建、临时服务端夹具完成；真实客户端联调待最终收口
- 适用版本：Minecraft 1.19.2 / Forge 43.2.0
- 参考：1.20.1 `1c81eb7`（退款与 schema）、`6f11f68`（热重载一致性）
- 协议与 API：保持不变，协议仍为 `"2"`，未修改 `com.wanancat.furkin.api`

---

## 1. 根因

### 1.1 M-02：退款账目与技能定义

旧实现只保存技能等级，洗点按 `Σ等级` 退款：

- `cost != 1` 的技能退款不足或多退；
- 热改 `cost` 后，历史已支付金额无法还原；
- `cost <= 0`、`maxLevel < 1`、`tier < 1`、非正 `requiresLevel` 可进入运行时；
- 悬空 `requires` / `requiresLevel` / `levelGate` 会让技能永久不可达，且没有加载期提示。

1.19.2 工作流的冻结口径见 `code_review_2026-09-25_workflow.md` §13.7：采用选项 A，非法定义拒绝加载；退款按实际累计支付点数计算，旧档一次性迁移。

### 1.2 M-04：热重载与流血语义

`SkillRegistry.reloadTree` 会替换技能树和派生缓存，但原实现没有重建已加载实体上的持久技能效果：

- `AttributeModifier` 随实体 NBT 保留，删除技能、切换属性目标或改数值后可能留下旧 modifier；
- `SkillEffectApplier.removeAll` 需要当前树中存在该技能，技能被删除时无法定位旧效果；
- `BleedingEffect` 每 tick 重新读取规格，属于实时语义，但原文档没有把它写成明确契约。

---

## 2. 冻结口径与实现语义

### 2.1 schema 严格校验（选项 A，小口径）

`SkillLoader` 对候选技能执行以下检查；违反任一条即拒绝该技能并记录 `WARN`：

- `cost >= 1`；
- `maxLevel == -1` 或 `maxLevel >= 1`；
- `tier >= 1`；
- `requiresLevel` 必须是对象，且其中每个值 `>= 1`；
- `requires`、`requiresLevel`、`levelGate` 的目标技能必须存在；
- `requiresLevel` 不得超过目标技能的有限 `maxLevel`；目标 `maxLevel == -1` 时不设目标上限。

加载采用两阶段流程：先收集候选，再做收敛式引用拒绝。指向被拒技能的依赖技能也会被拒绝，避免留下永久锁死的引用链。

本 WP 不混入 L-03（枚举容错）和 L-04（重复技能 ID 静默覆盖）；这两项仍按工作流留给 WP-08。

### 2.2 实际支付与退款

- 新增 `skill_investments`：技能 id → 实际累计支付技能点。
- 实体能力数据与服务器级绒亲档案都持久化该字段；`syncNBT()` 不写该字段，网络包结构不变。
- 加点成功时按当前 `cost` 扣除，并把同一 `cost` 追加到累计支付额。
- 洗点按累计实际支付额退款；在场路径和未召唤档案路径分别结算。
- 旧实体/旧档案缺少字段时标记为 unknown，首次加点或洗点按当前技能定义一次性迁移。
- 迁移时技能定义已删除，无法还原历史成本，按每级 `cost = 1` 保守兜底并记录 `WARN`；溢出值钳制到 `Integer.MAX_VALUE` 并记录 `WARN`。

### 2.3 热重载重建

- 所有 `furkin:attribute` modifier 使用固定名称 `furkin.skill.attribute`。
- `AttributeEffect.clearAll` 遍历属性注册表，对实体实际支持的属性按该固定名称清除本模组 modifier。
- `SkillEffectApplier.rebuildAll` 先清理属性 modifier，再按当前技能树和 `skillLevels` 重挂。
- `SkillRegistry.reloadTree` 只置位“待重建”标志；`SkillRuntimeCalibrator` 在服务器 tick 末尾消费标志，遍历 `getAllLevels()` 与 `getAllEntities()` 中已加载的绒亲。
- 不加载区块、不扫描未加载档案、不引入普通 tick 轮询。
- 重载时未加载的实体在再次入世时由 `CommonEvents#onEntityJoinLevel` 校准；召唤重建走同一 `rebuildAll` 路径；解绑清理走 `clearAll`。

### 2.4 流血实时语义

`BleedingEffect` 每次结算都读取当前 `BleedingSpec`，因此：

- `/reload` 后已有流血立即使用新的 `damagePerSecond`；
- 已经施加的 `MobEffectInstance` 持续时间不被重载改写；
- 技能定义删除或参数失效后，已有流血不再造成伤害，并自然到期。

---

## 3. 修改文件

新增：

- `src/main/java/com/wanancat/furkin/internal/skill/SkillRuntimeCalibrator.java`

修改：

- `src/main/java/com/wanancat/furkin/internal/skill/SkillLoader.java`
- `src/main/java/com/wanancat/furkin/internal/skill/SkillProgress.java`
- `src/main/java/com/wanancat/furkin/internal/skill/SkillEffectApplier.java`
- `src/main/java/com/wanancat/furkin/internal/skill/SkillRegistry.java`
- `src/main/java/com/wanancat/furkin/internal/skill/effect/AttributeEffect.java`
- `src/main/java/com/wanancat/furkin/internal/skill/BleedingSpec.java`
- `src/main/java/com/wanancat/furkin/internal/capability/FurkinData.java`
- `src/main/java/com/wanancat/furkin/internal/record/FurkinArchiveEntry.java`
- `src/main/java/com/wanancat/furkin/internal/contract/FurkinCompanionManager.java`
- `src/main/java/com/wanancat/furkin/internal/contract/FurkinUnbindCleanup.java`
- `src/main/java/com/wanancat/furkin/internal/event/CommonEvents.java`

没有修改公开 API、网络包字段、注册 ID 或协议版本。

---

## 4. 1.19.2 API 取证

已对本机 1.19.2 mapped official jar 核对实际符号：

`C:\Users\wanancat\.gradle\caches\forge_gradle\minecraft_user_repo\net\minecraftforge\forge\1.19.2-43.2.0_mapped_official_1.19.2\forge-1.19.2-43.2.0_mapped_official_1.19.2.jar`

- `ServerLevel#getAllEntities()`
- `MinecraftServer#getAllLevels()`
- `AttributeMap#hasAttribute(...)`
- `AttributeInstance#getModifiers()`
- `AttributeModifier#getName()` / `getId()`
- `CompoundTag.TAG_COMPOUND`
- `ForgeRegistries.ATTRIBUTES.getValues()`
- 临时夹具使用：`MinecraftServer#reloadResources(Collection<String>)`、`MinecraftServer#getPackRepository().getSelectedIds()`、`FakePlayerFactory`、`MobEffectInstance#getDuration()`

这些结论来自 1.19.2 符号检查，不依据 1.20.1 的存在性推断。

---

## 5. 验证

### 5.1 静态构建

夹具删除后执行：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat clean build --console=plain
```

结果：`BUILD SUCCESSFUL in 13s`（8 actionable tasks）；产物为 `build/libs/furkin-1.19.2-0.0.1.0.jar`，扫描未发现 `Wp05` / `wp05` / `WP05` 条目。

### 5.2 临时服务端夹具

2026-09-25 的夹具运行使用临时 `Wp05ServerFixture` 与 `data/furkin/skills/wp05_*.json`，验证后均删除。归档日志：

- `run/logs/2026-09-25-1.log.gz`
- 关键结果：`[WP05-FIXTURE] SUMMARY pass=30 fail=0 failed=none`
- 同次启动：`Done (2.283s)!`

覆盖项：

1. 合法 `cost=2`、`maxLevel`、`tier` 与引用定义能加载；
2. `cost=0/-1`、`maxLevel=0/-2`、`tier=0`、`requiresLevel=0` 被拒绝；
3. `requires` / `requiresLevel` / `levelGate` 悬空引用被拒绝；
4. `requiresLevel` 超过目标有限 `maxLevel` 被拒绝；
5. 被拒技能的依赖链级联拒绝；
6. 实体能力 NBT 与档案 NBT 的实付表往返；
7. `syncNBT()` 不包含实付表；
8. 旧实体 / 旧档案缺少键时 unknown 标记正确；
9. 首级加点记录实际支付额，热改 `cost` 后只影响后续支付；
10. 在场洗点与旧档案洗点按累计实付额退款；
11. 属性 modifier 重建、真实资源重载后不重复；
12. 已加载实体扫描包含绒亲；
13. 删除技能后旧属性 modifier 被清除；
14. 已施流血在重载中保持持续时间并继续读取当前规格。

该归档日志中未发现项目包自身的 `ERROR` / `FATAL`。真实 `/reload` 会触发 Forge/原版 `TagLoader` 的大型 tag 缺失错误；这些属于开发环境数据包重载噪声，不是本 WP 代码抛出的异常。

### 5.3 夹具删除后的服务端冒烟

```powershell
.\gradlew.bat runServer --console=plain
```

结果：`Done (2.402s)! For help, type "help"`；`run/logs/latest.log` 记录 `Furkin loaded 13 skills.`，项目包自身没有 `ERROR` / `FATAL` / 异常栈。该轮不包含 WP-05 夹具。

### 5.4 未完成与残余验证

- 未执行真实客户端 `/reload`、技能面板加点/洗点和数值变化的完整界面联调；WP-06 后统一补客户端验收。
- 未在真实客户端观察一次流血伤害数值在重载前后的变化；夹具已覆盖持续时间保持和当前规格读取路径。
- 未做独立进程重启后的旧属性 NBT 回放；夹具完成了 NBT 往返与再次入世校准。
- 旧档中被删除且曾使用非 1 成本的第三方技能无法还原精确历史支付额，只能按 `cost=1` 兜底。
- 本轮不改变网络格式，不需要因 WP-05 重新做双端协议拒绝矩阵。

---

## 6. 关闭结论

WP-05 的代码、静态构建、服务端夹具与文档已齐备。M-02 的点数账目和加载期 schema 边界已收口，M-04 的已加载实体重建、入世校准和流血实时语义已明确。真实客户端联调属于工作流定义的最终客户端验收步骤，不阻塞本 WP 的代码关闭。