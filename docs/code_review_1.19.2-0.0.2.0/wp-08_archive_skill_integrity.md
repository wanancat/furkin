# WP-08 存档与技能数据完整性加固（L-03 + L-04）

- 状态：实现、构建和临时服务端夹具验证完成；真实损坏磁盘存档的完整读档演练保留为残余
- 日期：2026-09-25
- 基线：WP-07 后的工作树
- 对应问题：枚举 `Enum.valueOf` 无容错；技能同 ID 静默覆盖
- 适用范围：Minecraft 1.19.2 / Forge 43.2.0

---

## 1. L-03：枚举反序列化容错

修复前：

- `FurkinData.deserializeNBT` 使用 `FurkinState.valueOf(...)`；键缺失时 `getString` 返回空串，同样抛异常。
- `FurkinData` / `FurkinArchiveEntry` 的 `combat_mode` 即使有键存在判定，值非法仍抛 `IllegalArgumentException`。
- `FurkinArchiveData.load` 逐条反序列化时没有隔离，单条损坏会向整个 `SavedData` 加载路径传播。

实施：

- `FurkinState` 增加大小写不敏感的 `parse(String)`；未知值返回 `null`。
- `FurkinData` 对 `state` 做容错解析：有完整 `companionId + ownerUuid` 时回退 `COMPANION`，否则回退 `WILD`，并记录含 companionId 的 `WARN`。
- `FurkinData` 与 `FurkinArchiveEntry` 对非法 `combat_mode` 回退 `FOLLOW` 并记录 `WARN`；旧档缺键仍按 `FOLLOW`，不产生噪声。
- `FurkinArchiveData.load` 对每条档案增加独立 `try/catch`；损坏条目跳过，保留同一列表中的其它有效条目，并记录条目索引和异常摘要。

回退口径的选择：非法身份值若已有完整绑定，优先保留“已契约”语义，避免把玩家宠物误降为 `WILD`；只有缺少身份绑定时才回退 `WILD`。`FOLLOW` 是既有旧档缺省，适合作为非法战斗模式回退。

## 2. L-04：技能重复 ID

修复前 `SkillLoader` 使用 `candidates.put(skill.getId(), skill)`，多个文件声明同一 `id` 时后加载定义静默覆盖先加载定义，日志和运行态都无法判断实际生效的是哪份数据。

实施：

- `SkillLoader` 维护候选 ID 到来源文件位置的映射。
- 首次定义写入候选；后续同 ID 定义被忽略并记录 `WARN`，日志同时包含技能 ID、双方来源。
- 不把重复 ID 当作整包致命错误，其它技能继续加载；不改变技能树、效果、存档或网络格式。

## 3. 临时夹具与证据

夹具在 `run/wp08-fixture.flag` 存在时运行，覆盖 7 项检查：

1. `FurkinState.parse` 大小写不敏感。
2. 非绑定实体的非法 `state` 回退 `WILD`。
3. 非法 `combat_mode` 回退 `FOLLOW`。
4. 有完整绑定的非法 `state` 回退 `COMPANION`。
5. 档案条目的非法 `combat_mode` 回退 `FOLLOW`。
6. 档案列表含一条无法解析的条目时，有效条目保留、坏条目被跳过。
7. 临时数据包中两份同 ID 技能文件只加载一份。

夹具日志：

```text
Duplicate skill id wp08:duplicate from wp08:skills/duplicate_b.json ignored; already loaded from wp08:skills/duplicate_a.json
Invalid furkin state 'NOT_A_STATE' for companion null; falling back to WILD
Invalid furkin combat mode 'NOT_A_MODE' for companion null; falling back to FOLLOW
Invalid furkin state 'NOT_A_STATE' for companion ...; falling back to COMPANION
Invalid archive combat mode 'NOT_A_MODE' for companion ...; falling back to FOLLOW
Skipping invalid furkin archive entry at index 1: net.minecraft.ResourceLocationException: ...
[WP08-FIXTURE] SUMMARY pass=7 fail=0
```

原始日志保存在 `run/logs/2026-09-25-2.log.gz`。夹具类、临时数据包和 flag 均已删除；`clean build` 后产物不含 `Wp08` / `wp08` 条目。

## 4. 静态与构建证据

- `compileJava`：`BUILD SUCCESSFUL`。
- `clean build`：`BUILD SUCCESSFUL`。
- 全量 `internal` 检索已无 `Enum.valueOf(...)` 调用；剩余 `String.valueOf` 是数值转字符串，不属于枚举解析。
- 无夹具 `runServer` 达到 `Done (2.259s)!`，加载 13 个内置技能，项目包无 `ERROR` / `FATAL`。

## 5. 残余风险

- 夹具直接验证了 `FurkinArchiveData.load`、`FurkinData.deserializeNBT` 和 `FurkinArchiveEntry.deserializeNBT` 的坏数据路径，但没有把损坏 NBT 写入真实世界区域文件后再重启读档；该端到端磁盘演练仍是残余。
- 重复 ID 的成功/忽略顺序依赖资源管理器的资源枚举顺序；当前实现明确为“加载顺序首个定义保留”，并通过日志暴露所有冲突，不把顺序差异隐藏在静默覆盖中。
