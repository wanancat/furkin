# WP7c：1.19.2 创造模式标签页 API

## 结论

1.19.2 继续保留自建 furkin 品牌标签页是可行的，改用 Forge 43.2.0 的旧式
`CreativeModeTab` 构造方式即可，不需要反射、Mixin 或手工替换 `CreativeModeTab.TABS`。

## 官方 API 取证

在 `forge-1.19.2-43.2.0_mapped_official_1.19.2.jar` 上通过 javap 确认：

- 没有 `CreativeModeTab.Builder`
- 没有 `Registries.CREATIVE_MODE_TAB`
- 没有创造标签页注册事件
- 存在 Forge 提供的 `CreativeModeTab(String label)`
- 该构造器调用 `this(-1, label)`
- 内部 `addGroupSafe(-1, this)` 会扩容并写入 `CreativeModeTab.TABS`

因此下面这种写法本身就是 1.19.2 的官方扩展路径：

```java
public static final CreativeModeTab FURKIN_TAB = new CreativeModeTab("furkin") {
    @Override
    public ItemStack makeIcon() {
        return new ItemStack(ModItems.FURKIN_CONTRACT.get());
    }
};
```

## 物品进入标签页与搜索页

1.19.2 的物品分类通过 `Item.Properties.tab(CreativeModeTab)` 设置。该参数最终写入
`Item.category`，而 `Item.allowedIn` 的判定逻辑是：

- 当前标签页等于物品分类时允许进入；
- 当前标签页是 `TAB_SEARCH` 时也允许进入。

所以四个物品只需要：

```java
new Item.Properties().tab(ModCreativeTab.FURKIN_TAB)
```

这样既进入 furkin 品牌标签页，也能被原版创造搜索页找到。

不覆写 `fillItemList`。父类实现会遍历物品注册表并调用每个物品的
`fillItemCategory`，已经覆盖本模组需求，重复实现只会增加维护面。

## 背景兼容

1.20.1 当前代码未指定标签页背景，Builder 默认值是 `items.png`。

1.19.2 的 `CreativeModeTab` 默认背景后缀同样是 `items.png`，所以迁移后
不需要新增背景贴图，也不需要调用 `setBackgroundImage`。

## 采用与后备方案

### 采用方案

静态创建旧式 `CreativeModeTab`，四个物品使用 `.tab(FURKIN_TAB)` 设置分类。

### 后备方案

如果后续需要向标签页加入不能通过 `Item.Properties.tab` 表达的动态物品，
可以在匿名类中覆写 `fillItemList`。注意：这只解决标签页展示，不一定能同步解决
搜索页收录，届时必须重新核对 `Item.allowedIn` 语义。

不使用直接修改 `CreativeModeTab.TABS` 的方案，因为 `CreativeModeTab(String)`
已经通过 Forge 的 `addGroupSafe` 安全完成同一件事。

## 相关文件

- `ModCreativeTab.java`
- `FurkinContractItem.java`
- `FurkinRecordItem.java`
- `FurkinRespecPotionItem.java`
- `FurkinSoulstoneItem.java`
- `FurkinMod.java`（移除不再需要的标签页注册调用）
