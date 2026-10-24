package org.DJB.easyelevator.network;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.DJB.easyelevator.Easyelevator;
import org.DJB.easyelevator.entity.AbstractCabinEntity;

/**
 * 右键交互事件（Forge 总线，两端）：站在轿厢里右键 -> 打开选站面板。
 *
 * <p>在整体架构中的位置：对应 Fabric 版的 {@code UseItemCallback}（对空处/物品使用）与
 * {@code UseBlockCallback}（对方块使用）两个全局回调。NeoForge 用
 * {@link PlayerInteractEvent.RightClickItem} / {@link PlayerInteractEvent.RightClickBlock}
 * 覆盖同样两个时机，判定条件逐条保持一致：</p>
 * <ul>
 *   <li>只看主手：副手会随主手重复触发；</li>
 *   <li>潜行 + 空手是拆除轿厢的保留组合，不能与之抢事件（{@code isSneaking} 直接放行）；</li>
 *   <li>旁观者不应能操作轿厢；</li>
 *   <li>用玩家自身碰撞箱查询，等价于"玩家是否站在轿厢内部的 3x3 空间里"。</li>
 * </ul>
 *
 * <p>返回值语义：站在轿厢里时取消事件并返回 {@link InteractionResult#SUCCESS}——
 * 客户端借此阻止"拿着方块右键把方块放出去"等后续处理，服务端借此阻止方块自身的使用逻辑；
 * 没站在轿厢里时一概放行（PASS），把这次使用让给楼层门/轨道等其它方块逻辑。</p>
 */
@EventBusSubscriber(modid = Easyelevator.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class InteractEvents {
    private InteractEvents() { }

    /** 对空处/手持物品右键（对应 Fabric 的 UseItemCallback）。 */
    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (openPanelIfPassenger(event.getEntity(), event.getHand())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    /** 视线命中方块时右键（对应 Fabric 的 UseBlockCallback）。 */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (openPanelIfPassenger(event.getEntity(), event.getHand())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    /**
     * 统一的"是否站在轿厢里"判定与开面板动作。
     *
     * @param player 交互的玩家
     * @param hand 使用的手
     * @return 玩家是轿厢乘客并已（在服务端）下发面板时为 true——调用方应据此取消事件
     */
    private static boolean openPanelIfPassenger(Player player, InteractionHand hand) {
        // 只看主手：副手会随主手重复触发；潜行 + 空手是拆除轿厢的保留组合，不能与之抢事件。
        if (hand != InteractionHand.MAIN_HAND || player.isSpectator() || player.isShiftKeyDown()) return false;
        Level level = player.level();
        for (var cabin : level.getEntitiesOfClass(AbstractCabinEntity.class,
                player.getBoundingBox(), c -> c.containsPassenger(player))) {
            // 仅在逻辑服务端发包；客户端取消事件即可，否则会收到重复面板。
            if (!level.isClientSide) ElevatorNetworking.open((net.minecraft.server.level.ServerPlayer) player, cabin);
            return true;
        }
        return false;
    }
}
