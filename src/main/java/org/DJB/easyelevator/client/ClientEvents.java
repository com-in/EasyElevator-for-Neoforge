package org.DJB.easyelevator.client;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.DJB.easyelevator.Easyelevator;
import org.DJB.easyelevator.entity.AbstractCabinEntity;
import org.DJB.easyelevator.logic.ElevatorController;
import org.DJB.easyelevator.network.ElevatorNetworking;

import java.util.HashMap;
import java.util.Map;

/**
 * 客户端游戏事件订阅（Forge 总线，仅客户端 dist）：每刻回调与断线清理。
 *
 * <p>在整体架构中的位置：对应 Fabric 版 {@code EasyelevatorClient} 里的
 * {@code ClientTickEvents.END_CLIENT_TICK} 与 {@code ClientPlayConnectionEvents.DISCONNECT}。
 * 客户端只读服务端同步的状态——MotionFrame 自定义包（绝对 double 位置）+ SynchedEntityData
 * （PHASE、DOOR、FACING、TARGET_Y），本类负责运行声（CabinRunningSound）的启停与门音效资源包维护；
 * 移动包的权威校验仍在服务端。</p>
 */
@EventBusSubscriber(modid = Easyelevator.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class ClientEvents {
    private ClientEvents() { }

    // 轿厢实体 id -> 正在播放的运行声，保证同一轿厢只有一条循环音效；断线与 Phase 离开 MOVING 时都会清理。
    private static final Map<Integer, CabinRunningSound> SOUNDS = new HashMap<>();

    /**
     * 门音效资源包是否已经在本会话里检查过（只需检查一次：包在磁盘上，不在每次进服时变化）。
     *
     * <p>为什么需要这个标记：客户端进服时会让 {@link DoorSoundPack#ensureReady} 扫包并可能启用它，
     * 那是唯一需要它的时刻；每刻都扫一次纯属浪费。</p>
     */
    private static boolean doorSoundPackChecked;

    /**
     * 本刻是否收到过新的门槽音频、等着把它重建进资源包。
     *
     * <p>存在的理由：进服补齐会连续下发多条 {@code DoorSoundData}，逐条触发 {@code reloadResourcePacks()}
     * 会把整个资源管理器重建很多次（几百毫秒的卡顿叠加）。攒到刻末做一次即可，
     * 因为玩家不可能在同一刻内分辨出两次重载的差别。</p>
     */
    private static boolean doorSoundReloadPending;

    /** 由 {@code ClientPayloadHandlers} 在收到新音频时调用：标记本刻末尾需要重建资源包。 */
    static void flagDoorSoundReload() {
        doorSoundReloadPending = true;
    }

    /**
     * 客户端每刻末尾（对应 Fabric 的 END_CLIENT_TICK）：拖拽重试、门音效资源包、运行声维护。
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        // 拖拽回调的重试：窗口可能在客户端初始化时还没建好，那时挂不上；
        // 挂上之后 register 内部一行判断就返回，每刻调用没有代价。
        FileDropHandler.register(client);
        // 进服后的第一刻：把磁盘上的运行时资源包接上，并向服务端索取本机缺少的自定义音频。
        // 只做一次。注意 DoorSoundPack.ensureReady 内部会先比对内容指纹——音频没变时
        // <b>不会</b>重载资源，因此进存档这条最常走的路不会闪红屏（见该方法的说明）。
        if (!doorSoundPackChecked && client.level != null) {
            doorSoundPackChecked = true;
            DoorSoundPack.ensureReady(client);
            // 请服务端把它现有的全部门槽音频发过来；服务端逐条回 DoorSoundData，本机按需存盘并重载。
            // 这样"音频上传之后才进服的玩家"也能听到同一段音频（专用服务器同样成立）。
            PacketDistributor.sendToServer(
                    new ElevatorNetworking.RequestDoorSound(ElevatorNetworking.RequestDoorSound.ALL_SLOTS));
        }
        // 本刻收到过新的门槽音频：一次性把它们重建进资源包并重载。
        // 放在刻末而不是收包处，是为了把"进服补齐"这种连续多条合并成一次重载。
        if (doorSoundReloadPending) {
            doorSoundReloadPending = false;
            DoorSoundPack.ensureReady(client);
        }
        // 先清理：实体卸载/移除或 Phase 离开 MOVING（到站、受阻、卡在门口）就停止运行声并移出映射；
        // removeIf 内返回 true 表示删除该条目，与 stop() 一样都是幂等的。
        SOUNDS.entrySet().removeIf(entry -> {
            var e = client.level == null ? null : client.level.getEntity(entry.getKey());
            if (!(e instanceof AbstractCabinEntity cabin) || cabin.isRemoved() || cabin.phase() != ElevatorController.Phase.MOVING) {
                client.getSoundManager().stop(entry.getValue());
                return true;
            }
            return false;
        });
        if (client.level == null) return;
        // 再补充：只为本刻处于 MOVING 的轿厢建立音效；computeIfAbsent 保证不会重复播放同一条循环。
        // 用实体 id 而非实体引用作键，避免实体对象在卸载后被旧映射长期持有。
        for (var e : client.level.entitiesForRendering())
            if (e instanceof AbstractCabinEntity cabin && cabin.phase() == ElevatorController.Phase.MOVING)
                SOUNDS.computeIfAbsent(cabin.getId(), id -> {
                    var sound = new CabinRunningSound(cabin);
                    client.getSoundManager().play(sound);
                    return sound;
                });
    }

    /**
     * 断线：停止所有循环音效并清空映射，同时丢弃 CabinMotion 的插值历史与本地乘客标记，
     * 否则进入新世界后可能拿旧世界的样本继续插值（实体 id 会复用）。
     */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        Minecraft client = Minecraft.getInstance();
        SOUNDS.values().forEach(client.getSoundManager()::stop);
        SOUNDS.clear();
        CabinMotion.clear();
        // 换个服务器/存档要重新检查资源包与补齐音频：不同的服务器可能配了不同的自定义音效。
        doorSoundPackChecked = false;
        doorSoundReloadPending = false;
    }
}
