package com.wanancat.furkin.internal.skill;

import com.wanancat.furkin.internal.FurkinMod;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 技能注册中心 —— 全局持有加载好的 {@link SkillTree}（设计稿 §3.2）。
 *
 * <p>技能树是「数据驱动 JSON 加载出的只读视图」，在资源加载 / 重载（数据包重载、
 * 进服）时刷新。通过 {@link AtomicReference} 保证跨线程可见；运行时只读。</p>
 *
 * <p>提供 {@link #reloadListener()} 注册到服务端 / 通用资源重载事件，
 * 以及 {@link #tree()} 取当前视图（加载失败时返回空树兜底）。</p>
 */
public final class SkillRegistry {

    private static final AtomicReference<SkillTree> TREE = new AtomicReference<>(SkillTree.empty());

    private SkillRegistry() {
    }

    /** 取当前技能树（只读视图）。 */
    public static SkillTree tree() {
        return TREE.get();
    }

    /** 加载技能树（由资源重载监听器调用）。 */
    public static void reloadTree(ResourceManager manager) {
        SkillTree tree = new SkillTree();
        SkillLoader.load(manager, tree);
        TREE.set(tree);
    }

    /** 构建一个资源重载监听器，注册到模组加载时的事件。 */
    public static SimplePreparableReloadListener<Void> reloadListener() {
        return new SimplePreparableReloadListener<>() {
            @Override
            protected Void prepare(ResourceManager manager, ProfilerFiller profiler) {
                return null;
            }

            @Override
            protected void apply(Void nothing, ResourceManager manager, ProfilerFiller profiler) {
                reloadTree(manager);
            }
        };
    }
}
