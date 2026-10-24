package org.DJB.easyelevator.client;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.IModBusEvent;
import net.neoforged.neoforge.data.event.GatherDataEvent;
import org.DJB.easyelevator.Easyelevator;

/**
 * 数据生成入口（NeoForge Data Generation）。
 *
 * <p>作用：在开发环境通过 {@code runData} 任务离线产出模组自带的 JSON 资源/数据
 * （配方、战利品表、方块状态、模型、标签等），产物写入生成的资源包，从而不必手写这些文件；
 * 它不参与游戏运行时逻辑，打包后的模组也不会执行它。</p>
 *
 * <p>与 Fabric 版的差异：Fabric 用 {@code DataGeneratorEntrypoint} 入口点；NeoForge 改为订阅
 * 模组总线的 {@link GatherDataEvent}。当前没有注册任何 provider（本模组的 JSON 全部手写且稳定），
 * 先保留这个订阅点以便后续新增 provider。</p>
 */
@EventBusSubscriber(modid = Easyelevator.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class EasyelevatorDataGenerator {
    private EasyelevatorDataGenerator() { }

    @SubscribeEvent
    public static void gatherData(GatherDataEvent event) {
        // 没有注册任何 FabricDataProvider/NeoForge Provider：现阶段运行数据生成不会产出文件。
    }
}
