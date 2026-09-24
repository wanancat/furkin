# Furkin 1.20.1 代码审查报告

- 审查日期：2026-09-24
- 工作目录：`D:\frukin_dev\frukin_1_20_1`
- 分支：`main`
- HEAD：`66dd99b78357f224c66aafe6faf9aa76a6a8a78e` / `fix: 补流血效果图标并升版至 1.20.1-0.0.1.1`
- 审查范围：`src/main/java`、`src/main/resources`、构建配置、资源加载、网络协议、客户端/服务端边界
- 审查类型：只读静态审查
- 代码修改：无
- 提交：无
- 结论：发现 9 项值得记录的问题，其中 3 项高严重度、4 项中严重度、2 项低严重度或待运行确认。

> 说明：本文记录的是当前 HEAD 的审查结论。绝对路径、行号和行为都应在后续代码变更后重新核对。

---

## 1. 审查摘要

### 1.1 高严重度

1. 契约确认包缺少服务端权威校验，可绕过正常契约前置。
2. 解绑未清理行囊、装备和战斗 AI，可能永久丢失物品并留下行为残留。
3. 绒亲档案按当前维度读取，跨维度会拆成多份。

### 1.2 中严重度

4. 网络协议版本未随包结构变化递增。
5. 洗点退款未乘技能 `cost`，技能 schema 缺少加载期校验。
6. `FurkinCombatMode` 按基类删除 goal，会误删原版或第三方 AI。
7. 技能热重载/数据包更新后，已有属性效果可能残留，流血效果语义会漂移。

### 1.3 低严重度 / 待运行确认

8. 共享网络包直接引用客户端类，专用服务端运行期隔离未验证。
9. 调试命令回执大量硬编码英文，违反项目玩家可见文本本地化规则。

### 1.4 建议修复顺序

1. 先修复契约确认包的服务端权威校验。
2. 再修复解绑清理和跨维度档案分裂。
3. 然后处理网络协议版本、技能退款/schema 校验、战斗 goal 和热重载一致性。
4. 最后处理端位纪律和命令本地化问题。

---

## 2. 验证范围与验证结果

### 2.1 项目规模

- Java 文件：87
- Java 行数：约 12,173 行
- 资源语言文件：
  - `src/main/resources/assets/furkin/lang/en_us.json`
  - `src/main/resources/assets/furkin/lang/zh_cn.json`
- 两者均为 117 个键，键集合一致。

### 2.2 已执行验证

构建命令：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat build --console=plain
```

结果：

- `BUILD SUCCESSFUL`
- Java 编译通过。
- 资源处理通过。
- 没有发现当前静态构建失败。

资源与语言检查：

- `src/main/resources/**/*.json` 已完成 JSON 解析检查。
- `mods.toml` 不是 JSON，不纳入 JSON 解析检查。
- `en_us.json` 与 `zh_cn.json` 的键集合一致。

### 2.3 已执行的针对性取证

- 使用 1.20.1 mapped official jar 的 `javap` 检查了：
  - `ServerLevel#getDataStorage()`
  - `ServerChunkCache` 的 `DimensionDataStorage` 构造
  - Forge `GoalSelector#removeAllGoals(Predicate)` 的使用语义
- 使用 Git 历史确认 `PROTOCOL_VERSION` 自初始加入后未变更。
- 对网络包注册、档案读取、契约、解绑、技能和战斗逻辑进行了全仓 `rg` 检索。

### 2.4 未执行验证

本轮没有执行：

```powershell
.\gradlew.bat runServer --console=plain
.\gradlew.bat runClient --console=plain
```

原因：

- 工作区不存在 `run/eula.txt`。
- 工作区不存在 `run/logs/latest.log`。
- 项目规则要求 Minecraft EULA 必须由用户本人同意，不能由代理代替接受。
- 因此不能声称服务端启动、客户端启动、客户端隔离或运行期网络行为已经验证通过。

### 2.5 工作区状态

审查期间未修改或提交代码。当前未跟踪文件包括：

- `AGENTS.md`
- `docs/contract_precondition_workflow.md`

其中 `docs/contract_precondition_workflow.md` 是未跟踪文档，不能被视为已经落地的安全修复，也不能据此认为契约前置问题已经解决。

---

## 3. 详细问题记录

## 高严重度

### H-01：契约确认包缺少服务端权威校验，可绕过契约前置

- 状态：已确认
- 严重度：高
- 公开影响：改造客户端可以伪造确认包，使不可契约或不符合条件的实体进入绒亲体系。

#### 位置

- `src/main/java/com/wanancat/furkin/internal/network/ConfirmContractPacket.java:42-54`
- `src/main/java/com/wanancat/furkin/internal/contract/FurkinContractHandler.java:55-91`
- `src/main/java/com/wanancat/furkin/internal/contract/FurkinContractHandler.java:104-179`
- `src/main/java/com/wanancat/furkin/internal/capability/FurkinAttachHandler.java:25-28`

#### 证据

正常交互路径中，`tryContract` 会检查：

- 目标类型是否已注册为可契约物种。
- 目标是否具有 `FurkinData` 能力。
- 目标是否已经不是 companion。
- 活跃宠物数量是否达到上限。

确认路径中，`ConfirmContractPacket.handle` 仅通过实体 ID 取得同维度实体：

```java
Entity entity = player.serverLevel().getEntity(packet.entityId);
if (entity instanceof LivingEntity target) {
    FurkinContractHandler.executeContract(player, target, player.getMainHandItem(), packet.name);
}
```

`executeContract` 只复检：

- 能力是否存在。
- 数据是否已经是 companion。
- 活跃数量是否达到上限。

它没有复检：

- 目标是否属于 `FurkinSpeciesRegistry` 中注册的物种。
- 目标是否存活、未移除、仍在可触及范围。
- 当前主手是否仍为 `FurkinContractItem`。
- 是否存在和当前目标绑定的待确认契约会话。
- 名字是否满足长度、内容或安全约束。

此外，`FurkinAttachHandler` 明确把能力挂到所有 `LivingEntity`，因此带能力本身不证明目标允许契约。

#### 影响

- 可以绕过注册物种检查，对任意带能力的活体实体写入 companion 身份。
- 可以绕过距离和存活检查。
- 可以在玩家切换主手后消耗错误物品，或者在空手/错误物品状态下完成契约。
- 可以形成没有经过 `tryContract` 的合约记录。
- 这是服务端权威状态被客户端直接推着走的问题，应优先修复。

#### 修复建议

1. 服务端维护短生命周期的待确认契约会话。
2. 会话应绑定：
   - 玩家 UUID
   - 目标实体 UUID 或稳定标识
   - 发起时的手持契约物品及数量
   - 目标类型与注册物种
   - 发起时间或 tick
3. 确认包处理时重新执行完整前置校验：
   - 注册物种
   - 能力存在
   - 未契约
   - 活跃上限
   - 存活、未移除、距离/可达
   - 主手仍是同一契约物品堆叠
   - 名字长度和内容合法
4. 只有会话校验通过后才消耗物品和建档。
5. 建议覆盖以下测试：
   - 伪造确认包，目标为未注册实体。
   - 伪造确认包，目标为玩家。
   - 发起命名后切换主手，再确认。
   - 发起命名后目标死亡或被移除，再确认。
   - 连续发送两次确认包，确认不会重复建档或重复消耗。

---

### H-02：解绑未清理行囊、装备和战斗 AI，可能永久丢物品

- 状态：已确认
- 严重度：高
- 公开影响：解绑后可能出现行囊物品隐藏、盔甲永久丢失和战斗 AI 残留。

#### 位置

- `src/main/java/com/wanancat/furkin/internal/contract/FurkinRecordActionHandler.java:107-136`
- `src/main/java/com/wanancat/furkin/internal/contract/FurkinRecordActionHandler.java:494-520`
- `src/main/java/com/wanancat/furkin/internal/contract/FurkinContractHandler.java:133-135`
- `src/main/java/com/wanancat/furkin/internal/equipment/EquipmentSlots.java:90-96`
- `src/main/java/com/wanancat/furkin/internal/event/CommonEvents.java:276-280`

#### 证据

`unbind` 的正常路径是：

1. 查档案并校验主人。
2. 如果已召唤，找到实体并调用 `clearFurkinLayer`。
3. 删除档案条目。

`clearFurkinLayer` 只清：

- `companionId`
- `ownerUuid`
- `level`
- `xp`
- `skillPoints`
- `skillLevels`
- `combatMode`
- `state`
- `TamableAnimal` 的 TAME、主人和坐定状态
- `CustomName`

它没有处理：

- `FurkinData#getPouch()` 内的物品。
- 四个盔甲槽中的装备。
- 契约时由 `EquipmentSlots.sealDrops` 设置为 0 的 `ArmorDropChances`。
- 技能冷却表。
- 进食递减状态。
- 之前由 `FurkinCombatMode` 挂上的战斗 goal。

契约时：

```java
EquipmentSlots.sealDrops(target);
```

`sealDrops` 会把四件盔甲的掉落概率设置为 0。

解绑后实体已经不再是 companion，之后如果死亡，`markFallenIfCompanion` 会在 `!data.isCompanion()` 处提前返回，因此不会写死亡装备快照。

#### 影响

- 行囊内容失去正常访问入口，但数据仍留在实体能力对象中；重新契约后可能重新出现。
- 盔甲仍穿在普通动物身上，且掉落概率为 0；动物死亡后装备可能永久丢失。
- 档案已经删除，没有死亡快照可以恢复装备。
- `clearFurkinLayer` 明确不处理 `goalSelector` / `targetSelector`，所以此前挂上的攻击、护主或主动索敌目标可能继续存在。
- 冷却和进食状态可能跨解绑污染下一次契约。

#### 修复建议

在删除档案前，按统一顺序处理：

1. 掉落或清空行囊，复用 `PouchDrop.dropAll`。
2. 决定解绑时盔甲的处置语义：
   - 直接归还给主人；或
   - 掉落到世界；或
   - 明确保留在实体上，并恢复合理的掉落概率。
3. 恢复原版/第三方 AI 状态，不能只依赖注释声称“原版 AI 自行接管”。
4. 清理 cooldowns、feedCount、lastFeedMillis 等运行时状态。
5. 只有清理全部成功后再 `archive.removeEntry`。
6. 测试：
   - 解绑带满行囊的宠物。
   - 解绑穿四件盔甲的宠物。
   - 解绑后杀死实体，确认装备不会消失。
   - 解绑后重新契约，确认旧行囊和旧冷却不会回流。
   - 解绑前分别设置 FOLLOW、PASSIVE、PROTECT、AGGRESSIVE，确认无残留攻击行为。

---

### H-03：绒亲档案按当前维度读取，跨维度会拆成多份

- 状态：已确认
- 严重度：高
- 公开影响：跨维度后宠物可能从绒亲录消失、无法召唤/收回，活跃上限也可能被绕过。

#### 位置

- `src/main/java/com/wanancat/furkin/internal/record/FurkinArchiveData.java:81-90`
- 主要调用点：
  - `src/main/java/com/wanancat/furkin/internal/contract/FurkinCompanionManager.java:89,159,215,265,460,488`
  - `src/main/java/com/wanancat/furkin/internal/item/FurkinRecordItem.java:79`
  - `src/main/java/com/wanancat/furkin/internal/skill/SkillProgress.java:60,138,183,199`
  - `src/main/java/com/wanancat/furkin/internal/contract/FurkinRecordActionHandler.java:112,156,217,403`
  - `src/main/java/com/wanancat/furkin/internal/contract/FurkinCombatModeHandler.java:58`
  - `src/main/java/com/wanancat/furkin/internal/command/FurkinCommand.java:205,358`
  - `src/main/java/com/wanancat/furkin/internal/growth/FurkinGrowth.java:148`
  - `src/main/java/com/wanancat/furkin/internal/event/CommonEvents.java:285`
  - `src/main/java/com/wanancat/furkin/internal/contract/FurkinContractHandler.java:164`

#### 证据

当前实现：

```java
public static FurkinArchiveData get(ServerLevel level) {
    return level.getDataStorage()
            .computeIfAbsent(FurkinArchiveData::load, FurkinArchiveData::new, NAME);
}

public static FurkinArchiveData get(MinecraftServer server) {
    return get(server.overworld());
}
```

通过 1.20.1 mapped official jar 的 `javap` 可以确认：

- `ServerLevel#getDataStorage()` 调用 `getChunkSource().getDataStorage()`。
- `ServerChunkCache` 在构造器中创建自己的 `DimensionDataStorage`。
- 因此 `SavedData` 实例是维度级的，每个维度会各自创建一份 `furkin_archive`。

当前玩法调用点几乎都使用 `player.serverLevel()` 或实体所在 `ServerLevel`，而不是服务器的 overworld 单例。

#### 影响

- 主世界契约的宠物，进入下界/末地后可能从绒亲录里消失。
- 跨维度召唤、收回、改名、洗点、复活等操作会命中原维度之外的档案实例。
- 活跃上限按当前维度统计，玩家可能在不同维度各带一批宠物。
- 同一 `companionId` 在不同维度可能表现为不同状态。
- `get(MinecraftServer)` 当前实际上没有被玩法路径使用，说明全局档案路径存在但未落地。

#### 修复建议

1. 所有玩法路径统一改为读取服务器级 overworld 档案：
   - `FurkinArchiveData.get(player.getServer())`
   - 或 `FurkinArchiveData.get(player.server.overworld())`
2. 如果未来确实需要维度级数据，必须使用不同的存储 key，并明确设计跨维度查询。
3. 对所有跨维度入口补充验证：
   - 主世界契约后进入下界。
   - 下界查看绒亲录。
   - 下界点击召唤。
   - 主世界宠物在实体仍存在时跨维度查看。
   - 多维度同时检查活跃上限。

---

## 中严重度

### M-01：网络协议版本未随包结构变化递增

- 状态：已确认
- 严重度：中
- 公开影响：新旧版本模组混连时可能错包、解码失败或状态损坏。

#### 位置

- `src/main/java/com/wanancat/furkin/internal/network/FurkinNetwork.java:25-32`
- `src/main/java/com/wanancat/furkin/internal/network/FurkinNetwork.java:38-138`
- `src/main/java/com/wanancat/furkin/internal/network/RecordActionPacket.java:52-91`
- `src/main/java/com/wanancat/furkin/internal/network/RecordListPacket.java:147-197`

#### 证据

当前协议版本为：

```java
private static final String PROTOCOL_VERSION = "1";
```

Git 历史显示该值自加入后没有变更。

后续至少新增或扩展了：

- `UnlockSkillPacket`
- `ResetSkillsPacket`
- `OpenFurkinScreenPacket`
- `SelectTabPacket`
- 契约命名/确认包
- 绒亲录操作包
- `RecordActionPacket` 的 `combatMode`
- `RecordActionPacket` 的 `refreshRecord`
- `RecordListPacket` 的属性表

Forge `SimpleChannel` 的握手只比较 `PROTOCOL_VERSION`。版本字符串相同而消息 ID/字段布局不同，旧客户端和新服务端仍可能握手成功。

#### 影响

- 旧版本客户端可能收到它没有注册的消息 ID。
- 新版本字段可能让旧版本 decode 读错位置。
- 混连场景可能出现断连、异常、状态错位或错误动作。

#### 修复建议

1. 每次新增/删除/修改不兼容包或字段时递增 `PROTOCOL_VERSION`。
2. 发布时确认客户端与服务端协议版本一致。
3. 如果需要兼容旧版本，增加显式版本分支或迁移处理，而不是继续复用 `"1"`。

---

### M-02：洗点退款未乘技能 `cost`，技能 schema 也缺少校验

- 状态：已确认
- 严重度：中
- 公开影响：第三方数据包或自定义技能设置非 1 成本时会退错点；非法字段可能制造技能点。

#### 位置

- `src/main/java/com/wanancat/furkin/internal/skill/SkillProgress.java:102-107`
- `src/main/java/com/wanancat/furkin/internal/skill/SkillProgress.java:149-153`
- `src/main/java/com/wanancat/furkin/internal/skill/SkillProgress.java:169-173`
- `src/main/java/com/wanancat/furkin/internal/skill/SkillLoader.java:77-79`
- `src/main/java/com/wanancat/furkin/internal/skill/Skill.java:118-120`

#### 证据

加点扣费：

```java
if (data.getSkillPoints() < skill.getCost()) {
    return Result.NOT_ENOUGH_POINTS;
}
data.setSkillPoints(data.getSkillPoints() - skill.getCost());
```

在场洗点退款：

```java
for (int lv : data.getSkillLevels().values()) {
    refund += lv;
}
```

未召唤时也是按档案中的等级直接累加：

```java
refund += entry.getSkillSnapshot().getInt(key);
```

没有乘以 `cost`。

`SkillLoader` 没有拒绝：

- `cost <= 0`
- `maxLevel == 0`
- 非 `-1` 的负 `maxLevel`
- 非法的 `tier`
- 非法的 `requiresLevel`

其中 `cost < 0` 时，加点会扣负数，等价于增加技能点。

#### 影响

- 当前内置技能 `cost` 全部为 1，因此内置内容暂时隐藏了该问题。
- 第三方技能或数据包设置 `cost = 2/3/...` 后，退款会少。
- 非法 `cost` 可能让技能变成免费或反向赚点。
- 非法等级字段会造成技能不可用、满级判断异常或其他静默错误。

#### 修复建议

1. 在加载期校验：
   - `cost > 0`
   - `maxLevel == -1` 或 `maxLevel >= 1`
   - `tier` 合法
   - `requiresLevel` 的等级大于 0
2. 退款时按实际支付成本计算。
3. 如果允许热改 cost，应记录玩家每次实际支付的技能点，而不是只记录等级。
4. 补充数据包测试：
   - `cost = 2`
   - `cost = 0`
   - `cost = -1`
   - `maxLevel = 0`
   - `maxLevel = -2`

---

### M-03：`FurkinCombatMode` 按基类删除 goal，会误删原版或第三方 AI

- 状态：已确认
- 严重度：中
- 公开影响：切换战斗模式可能永久改变原版/第三方 AI 配置。

#### 位置

- `src/main/java/com/wanancat/furkin/internal/contract/FurkinCombatMode.java:68-103`

#### 证据

`applyTo` 的第一步是：

```java
removeAllCombatTargets(animal);
```

`removeAllCombatTargets` 使用类型判断：

```java
animal.targetSelector.removeAllGoals(goal ->
        goal instanceof HurtByTargetGoal
                || goal instanceof OwnerHurtByTargetGoal
                || goal instanceof OwnerHurtTargetGoal
                || goal instanceof NearestAttackableTargetGoal);
animal.goalSelector.removeAllGoals(goal -> goal instanceof MeleeAttackGoal);
```

它没有检查 goal 是否由 Furkin 本模组添加。

对于原版狼或第三方模组动物，这些类型可能本来就是原版或第三方 AI 配置的一部分。

#### 影响

- 契约、切换模式、重新召唤时会删除非本模组 goal。
- 解绑后不会恢复，因为 `clearFurkinLayer` 有意不处理 goalSelector。
- 可能把原版/第三方动物 AI 永久改成另一套行为。

#### 修复建议

1. 为 Furkin 添加的 goal 使用自定义子类或显式标记。
2. 删除时只匹配本模组创建的具体实例。
3. 如果必须替换原有 goal，应保存原始 goal 列表并在解绑/模式切换时恢复。
4. 对原版狼、猫以及第三方 `TamableAnimal` 做兼容测试。

---

### M-04：技能热重载/数据包更新后，已有属性效果可能残留，流血语义会漂移

- 状态：已确认
- 严重度：中
- 公开影响：技能从数据包中删除或修改后，已存在实体的附加效果可能与当前技能定义不一致。

#### 位置

- `src/main/java/com/wanancat/furkin/internal/skill/SkillRegistry.java:33-42`
- `src/main/java/com/wanancat/furkin/internal/skill/SkillEffectApplier.java:33-68`
- `src/main/java/com/wanancat/furkin/internal/skill/effect/AttributeEffect.java:42-76`
- `src/main/java/com/wanancat/furkin/internal/effect/BleedingEffect.java:48-58`
- `src/main/java/com/wanancat/furkin/internal/skill/SkillPassiveDispatcher.java:732-738`
- `src/main/java/com/wanancat/furkin/internal/skill/BleedingSpec.java:107-109`

#### 证据

`SkillRegistry.reloadTree` 的行为是：

- 加载新 `SkillTree`。
- 替换静态树引用。
- 清空 `SkillParams` 和 `HarvestSpec` 缓存。

它没有遍历现有宠物并重新构建效果。

`SkillEffectApplier.removeAll` 使用传入的当前树：

```java
Skill skill = tree.get(skillId).orElse(null);
if (skill == null) {
    return;
}
```

如果数据包删除了某个技能，或者某个技能不再声明旧属性 effect，移除逻辑找不到旧定义，旧的 `AttributeModifier` 可能继续留在实体上。

流血效果则在每次 tick 时通过当前树查询：

```java
float damage = BleedingSpec.of()
        .map(spec -> spec.damageForLevel(amplifier + 1))
        .orElse(0.0f);
```

因此已经挂上的流血效果会在重载后直接使用新 JSON 的 DPS。

#### 影响

- 删除技能定义后属性加成可能残留。
- 修改技能属性目标时，旧属性 modifier 和新属性 modifier 可能同时存在。
- 已有流血的每秒伤害会在 `/reload` 后改变。
- 跨会话数据包更新也可能留下持久化属性 modifier。

#### 修复建议

1. 在重载完成后统一重建所有在场 companion 的技能效果。
2. 对已存在效果，先按旧定义移除，再按新定义应用。
3. 如果无法保留旧定义，至少维护兼容清理表。
4. 对流血这类临时效果，在施加时把参数快照写入 effect 实例，或者明确声明热重载会改变现有效果。
5. 添加以下测试：
   - 修改属性数值后 `/reload`。
   - 删除属性技能后 `/reload`。
   - 修改流血 DPS 后让已有流血继续 tick。
   - 保存退出再加载，检查旧 modifier 是否仍存在。

---

## 低严重度 / 待运行确认

### L-01：共享网络包直接引用客户端类

- 状态：静态发现；运行期未确认
- 严重度：低至中，取决于专用服务端类加载结果

#### 位置

- `src/main/java/com/wanancat/furkin/internal/network/SyncFurkinDataPacket.java:5`
- `src/main/java/com/wanancat/furkin/internal/network/RecordListPacket.java:3`
- `src/main/java/com/wanancat/furkin/internal/network/OpenFurkinScreenPacket.java:3`
- `src/main/java/com/wanancat/furkin/internal/network/RequestContractNamePacket.java:3`

#### 证据

这些包位于共享源码集，但直接 import 或引用客户端类：

- `net.minecraft.client.Minecraft`
- `FurkinRecordScreen`
- `FurkinPanelScreen`
- `ContractNameScreen`

调用点使用了 `DistExecutor.unsafeRunWhenOn(Dist.CLIENT, ...)`，因此编译通过。但项目规则明确要求共享逻辑不得引用 `net.minecraft.client.*`，并且专用服务端运行期类加载没有实机验证。

#### 影响与结论

- 当前不能仅凭编译结果断言专用服务端一定安全。
- 也不能仅凭静态引用断言一定崩溃。
- 需要在专用服务端运行 `runServer`，确认不存在 `NoClassDefFoundError` 或类加载问题。

#### 修复建议

1. 把客户端处理逻辑移动到明确的 client-only handler。
2. 共享包只保留协议数据、encode/decode 和服务端侧校验。
3. 使用 Forge 客户端分发入口时，把客户端类引用隔离到不在服务端加载的类中。
4. 在专用服务端执行启动与进服验证。

---

### L-02：调试命令回执大量硬编码英文

- 状态：已确认
- 严重度：低

#### 位置

- `src/main/java/com/wanancat/furkin/internal/command/FurkinCommand.java:167-185`
- `src/main/java/com/wanancat/furkin/internal/command/FurkinCommand.java:217-262`
- `src/main/java/com/wanancat/furkin/internal/command/FurkinCommand.java:301-338`
- `src/main/java/com/wanancat/furkin/internal/command/FurkinCommand.java:353-376`
- `src/main/java/com/wanancat/furkin/internal/command/FurkinCommand.java:407-472`
- `src/main/java/com/wanancat/furkin/internal/command/FurkinCommand.java:487-591`
- `src/main/java/com/wanancat/furkin/internal/command/FurkinCommand.java:623-644`

#### 证据

命令回执中存在大量 `Component.literal` 英文文本，例如：

- `"Your companions:"`
- `"[Fallen]"`
- `"[Summoned]"`
- `"Click to copy ID"`
- `"Invalid pet id: ..."`
- `"Not your companion."`
- `"Summon failed (internal error)."`
- `"Companion is not summoned — summon it first."`
- `"Added ... to ..."`

#### 影响

- 命令仅 OP 可用，影响范围有限。
- 但项目规则要求所有玩家可见文本使用翻译键。
- 语言切换时命令回执不会跟随本地化。

#### 修复建议

1. 所有命令回执改为 `Component.translatable`。
2. 在 `en_us.json` 和 `zh_cn.json` 同步补齐键。
3. 统一错误、成功、状态、数量等回执格式。

---

## 4. 已验证但未列为缺陷的候选

以下内容经过检查后没有作为当前缺陷列入主报告，记录在此以备后续维护：

- `en_us.json` 与 `zh_cn.json` 键集合一致。
- 资源 JSON 可解析。
- `mods.toml` 非 JSON，未误报。
- `SkillRegistry.reloadTree` 确实清理了 `SkillParams` 和 `HarvestSpec` 的旧缓存。
- 正常收回路径的 `PouchDrop` 与快照顺序基本正确。
- 正常死亡路径的装备快照与掉落封口顺序基本正确。
- `RecordAttributes` 的活跃/快照两路计算总体自洽。
- `PouchDrop`、`EquipmentSlots` 在正常收回/死亡流程中配对逻辑基本正确。
- `FurkinPouchMenu` 的可见槽位、Shift 搬运和装备槽限制有对应的实现说明。
- `SelectTabPacket` 明确记录了“未校验客户端页签”的已知边界，但它最多影响物品落入哪个容器，未发现直接复制或凭空生成物品的路径。

注意：以上“未列为缺陷”不等于运行时完全验证通过，只表示当前静态审查没有发现足以列为独立缺陷的证据。

---

## 5. 建议的修复批次

### 批次 1：服务端权威与持久数据安全

- 修复 H-01 契约确认包绕过。
- 修复 H-02 解绑清理。
- 修复 H-03 维度档案分裂。
- 完成后至少运行：
  - `build`
  - `runServer`
  - 对应 `runClient` 验证
  - 契约、解绑、跨维度召唤/收回/洗点测试

### 批次 2：协议与数据一致性

- 修复 M-01 协议版本。
- 修复 M-02 技能退款和 schema 校验。
- 修复 M-04 热重载效果重建。
- 补充数据包更新/降级/删除技能测试。

### 批次 3：AI 兼容与端位纪律

- 修复 M-03 战斗 goal 误删。
- 处理 L-01 客户端类引用边界。
- 在专用服务端执行启动和进服测试。

### 批次 4：本地化与文档

- 处理 L-02 命令回执本地化。
- 同步维护 `README.md`、`README.zh-CN.md`、`CHANGELOG.md`、`changelog.en.md`。
- 如技能 schema 或热重载语义发生变化，同步更新 `SKILL_TREE.md`。

---

## 6. 最终验证声明

本轮审查结论为静态代码审查结果。

- `build` 已通过。
- 资源 JSON 可解析。
- 语言键集合一致。
- 没有执行 `runServer` 或 `runClient`。
- 没有修改代码。
- 没有提交。
- 没有代替用户接受 Minecraft EULA。
- 所有运行期结论均以实际启动和业务场景验证为准。
