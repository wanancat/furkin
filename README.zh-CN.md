# Furkin（绒亲）

![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-blue)

![Forge](https://img.shields.io/badge/Forge-47.0%2B-orange)

![License](https://img.shields.io/badge/License-MIT-green)

轻量化的**伴侣宠物框架**：把原版动物契约成有成长、有技能树、能穿装备、可复活的伴侣宠物——**绒亲**，
并开放 API 让其他模组把自己的生物也接入成为绒亲。
本包原生支持猫猫和狗狗（因为它们相当毛~绒~绒~）

> English docs: [README.md](./README.md).

---

## 特性

- **契约** — 一张道具即可契约任意**已注册**物种，无需事先驯服。
- **技能树** — 共享主干 + 物种特色分支。
- **装备** — 对齐原版 4 槽，第三方盔甲零配置直接可穿。
- **随身行囊** — 随绒亲同行的背包，格数随 `travel_pouch` 技能等级派生，含缩容 / 回收 / 死亡掉落处理。终于有人替你搬圆石了。
- **复活** — 魂石制复活，成长不丢失。

## 依赖

| 需求             | 版本   |
| ---------------- | ------ |
| Minecraft        | 1.20.1 |
| Minecraft Forge  | 47.0+  |

**零强制前置。** 不需要任何依赖库模组。

## 安装

1. 安装 [Minecraft Forge 1.20.1](https://files.minecraftforge.net)（47.0 或更高）。
2. 从 releases 下载最新 `.jar`。
3. 放入你的 `.minecraft/mods` 文件夹。

## 用法

- 用羊毛和纸制作一张**绒亲契约**，右键任意有效动物即可将其契约成绒亲。
- 用绒亲契约和书制作一本**绒亲录**来管理你的绒亲（召唤、收回、解绑、切换战斗模式等）。

![绒亲录](./docs/images/companion_record.png)

- 解绑时，行囊和装备会掉落在实体当前位置，并恢复被接管的 AI 与原版默认掉率。若已召唤实体暂时无法解析，绒亲录会提供二次确认的强制解绑；实体以后入世时完成清理。
- 通过潜行+右键点击你的绒亲，为它升级技能、调整装备。它已经准备好跟你进行一次冒险了。

![技能面板](./docs/images/skill_panel.png)

- 技能加点不合心意吗？用绒亲契约和水瓶制作一瓶**洗点药水**吧。

- 当你的绒亲不慎阵亡，拾回它掉落的**绒亲魂石**，你还能将它带回人间。

![复活仪式](./docs/images/revive_ritual.png)

（为此，你可能需要一个羊毛和一些花……）

## 模组开发者

Furkin 可作为**前置模组**：依赖它，通过公开 API（`com.wanancat.furkin.api`）
注册你自己的物种、技能与效果。

```java
// 注册一个物种
FurkinApi.registerSpecies(EntityType.WOLF, ...);
```

任何已注册的物种都可以——哪怕是僵尸，只要你觉得它足够毛绒绒。:)

完整 API 见 `api` 包（7 个公开类型）。
⚠️ `api` 包以外的所有类型（尤其 `internal.*`）均为内部实现，不在兼容承诺范围内、随时可能变更。

## 从源码构建

```bash
# 需要 JDK 17
./gradlew build
```

模组 jar 产物位于 `build/libs/furkin-<version>.jar`。

运行开发客户端或服务器：

```bash
./gradlew runClient
./gradlew runServer
```

## 许可证

[MIT](./LICENSE.txt)。可自由使用、修改与再分发——包括整合包和作为依赖。

## 致谢

- **作者**：wanancat
- 基于 Minecraft Forge 模组框架构建。
