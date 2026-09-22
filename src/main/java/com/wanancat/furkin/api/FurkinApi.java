package com.wanancat.furkin.api;

import com.wanancat.furkin.api.companion.FurkinSpecies;
import com.wanancat.furkin.api.companion.FurkinSpeciesRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

import java.util.Optional;

/**
 * furkin 公开 API 静态入口（设计稿 §4）。
 *
 * <p>第三方模组通过本类注册物种、查询物种、查询 API 版本。所有方法均为静态，
 * 与 internal 实现解耦。</p>
 *
 * <p>本类处于 {@code api} 包，属「对外识别型」命名（设计稿 §9 判据②）。</p>
 */
public final class FurkinApi {

    /**
     * 当前 API 段版本号 —— 即模组版本号中的 {@code MAJORAPI} 段。
     *
     * <p>furkin 采用 <b>Forge 官方推荐的版本号格式</b>
     * {@code MCVERSION-MAJORMOD.MAJORAPI.MINOR.PATCH}
     * （Forge 文档《Versioning》），因此「API 版本」不是一个独立编号，
     * 而是模组版本号里的 <b>第三段</b>：</p>
     *
     * <pre>
     *   1.20.1-0.0.1.0
     *          ↑ ↑ ↑ ↑
     *          │ │ │ └─ PATCH
     *          │ │ └─── MINOR
     *          │ └───── MAJORAPI  ← 本值
     *          └─────── MAJORMOD
     * </pre>
     *
     * <p>官方规定：本段<b>只在 API 发生不兼容变更时递增</b>
     * （改枚举顺序、改方法返回类型、整体移除 public 方法），
     * 新增方法、废弃方法等兼容性改动<b>不会</b>改变它。</p>
     *
     * <p><b>初始开发阶段约定</b>（官方）：未正式发布前本段保持 {@code 0}；
     * 首次正式发布时随 {@code MAJORMOD} 一同进位为 {@code 1.0.0.0}。</p>
     */
    private static final int API_VERSION = 0;

    private FurkinApi() {
    }

    /**
     * 查询 furkin 的 API 版本号（模组版本号中的 {@code MAJORAPI} 段）。
     *
     * <p>第三方可据此判断自己的接入代码是否仍被支持，例如「要求 API 版本 ≥ 1」。</p>
     *
     * <p><b>与模组版本的关系</b>：本值取自版本号，二者同源而非各自独立。
     * 若需按整个模组版本约束依赖，请在自己的 {@code mods.toml} 里声明版本范围
     * （如 {@code versionRange="[1.20.1-0.0.1,)"}）—— 那是 Forge 在<b>加载期</b>校验的路径，
     * 比运行期查询更早、更可靠。</p>
     *
     * @return API 段版本号
     */
    public static int getApiVersion() {
        return API_VERSION;
    }

    /**
     * 注册一个可契约物种。
     *
     * @param id         物种标识（命名空间 + 名称，如 {@code yourmod:fox}）
     * @param entityType 对应的实体类型
     * @param nameKey    显示名本地化 key
     * @return 注册后的物种对象
     */
    public static FurkinSpecies registerSpecies(ResourceLocation id, EntityType<?> entityType, String nameKey) {
        return FurkinSpeciesRegistry.register(new FurkinSpecies(id, entityType, nameKey));
    }

    /**
     * 查询某实体类型是否已注册为可契约物种。
     */
    public static boolean isRegistered(EntityType<?> entityType) {
        return FurkinSpeciesRegistry.isRegistered(entityType);
    }

    /**
     * 按物种 ID 查询物种。
     */
    public static Optional<FurkinSpecies> getSpecies(ResourceLocation id) {
        return FurkinSpeciesRegistry.byId(id);
    }
}
