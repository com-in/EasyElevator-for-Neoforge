package org.DJB.easyelevator.client;

import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.DJB.easyelevator.logic.DoorSoundPersistence;
import org.DJB.easyelevator.network.ElevatorNetworking;

/**
 * S2C（服务端 -> 客户端）自定义负载的客户端处理器。
 *
 * <p>在整体架构中的位置：对应 Fabric 版 {@code EasyelevatorClient#onInitializeClient} 里注册的
 * 七个 {@code ClientPlayNetworking.registerGlobalReceiver}。NeoForge 的负载注册统一在
 * {@code RegisterPayloadHandlersEvent}（模组总线，两端都会执行）完成，因此这些处理器被搬到此处，
 * 由 {@link ElevatorNetworking#register} 引用。</p>
 *
 * <p><b>为什么每个方法都有 dist 守卫</b>：本类会被专用服务端加载（注册负载类型时需要创建方法引用），
 * 而方法体引用的界面/渲染类只存在于客户端 jar。JVM 对 invokedynamic 的引导与方法体的解析是惰性的，
 * 但守卫能确保服务端绝不会触达任何客户端代码——这是单源码集（client 与 main 同 jar）下的标准做法。</p>
 *
 * <p>所有回调都在网络线程被调用，客户端状态修改一律 {@code enqueueWork} 回客户端主线程，避免数据竞争。</p>
 */
public final class ClientPayloadHandlers {
    private ClientPayloadHandlers() { }

    /** 只有客户端进程才继续执行后续的客户端逻辑。 */
    private static boolean client() {
        return FMLLoader.getDist().isClient();
    }

    /** 轿厢运动帧：交给 {@link CabinMotion} 做承托与插值（内容全部来自包）。 */
    public static void motionFrame(ElevatorNetworking.MotionFrame payload, IPayloadContext context) {
        if (!client()) return;
        context.enqueueWork(() -> CabinMotion.receive(payload));
    }

    /** 打开站层面板：服务端已校验乘客身份与站点列表，直接开界面。 */
    public static void openPanel(ElevatorNetworking.OpenPanel payload, IPayloadContext context) {
        if (!client()) return;
        context.enqueueWork(() -> Minecraft.getInstance().setScreen(new ElevatorScreen(payload)));
    }

    /** 停靠计划变化：只刷新"已经为这辆轿厢打开的面板"，不会给没开面板的乘客弹界面。 */
    public static void panelState(ElevatorNetworking.PanelState payload, IPayloadContext context) {
        if (!client()) return;
        context.enqueueWork(() -> {
            if (Minecraft.getInstance().screen instanceof ElevatorScreen screen && screen.entityId() == payload.entityId())
                screen.applyState(payload.planned(), payload.baseFloorY());
        });
    }

    /** 厅外呼叫面板：右键楼层门时服务端下发，直接开界面。 */
    public static void openHallPanel(ElevatorNetworking.OpenHallPanel payload, IPayloadContext context) {
        if (!client()) return;
        context.enqueueWork(() -> Minecraft.getInstance().setScreen(new LandingDoorScreen(payload)));
    }

    /** 点亮状态刷新：只更新"已经为这个站点打开的面板"，绝不主动弹界面。 */
    public static void hallPanelState(ElevatorNetworking.HallPanelState payload, IPayloadContext context) {
        if (!client()) return;
        context.enqueueWork(() -> {
            if (Minecraft.getInstance().screen instanceof LandingDoorScreen screen && screen.station().equals(payload.station()))
                screen.applyState(payload.up(), payload.down());
        });
    }

    /** 门专属设置面板：打开或刷新界面（面板内容全部来自包，客户端不推导、不缓存）。 */
    public static void openDoorPanel(ElevatorNetworking.OpenDoorPanel payload, IPayloadContext context) {
        if (!client()) return;
        context.enqueueWork(() -> {
            // 已经为同一扇门开着面板时只刷新内容：避免每次点按钮都重建界面（会打断连点）。
            if (Minecraft.getInstance().screen instanceof DoorSoundScreen screen && screen.station().equals(payload.station()))
                screen.apply(payload.enabled(), payload.choice(), payload.baseFloor(), payload.preview());
            // 广播只刷新：不能抢走旁观者的游戏画面、背包或另一扇门的面板。
            else if (payload.open()) Minecraft.getInstance().setScreen(new DoorSoundScreen(payload));
        });
    }

    /**
     * 收到某个门槽的音频内容（服务端分发、或进服补齐的回应）：存进权威副本 -> 标记待重载。
     *
     * <p>只标记、不立刻重载：进服补齐会连着来很多条，逐条 {@code reloadResources} 会让资源管理器重建很多次
     * （每一次都弹那个红色 Mojang 画面）；真正的重载统一放在本刻末尾做一次（见 {@link ClientEvents}）。</p>
     */
    public static void doorSoundData(ElevatorNetworking.DoorSoundData payload, IPayloadContext context) {
        if (!client()) return;
        context.enqueueWork(() -> {
            if (payload.bytes().length == 0) return; // 服务端也没有这份音频：保持静音，不做无谓重载
            if (DoorSoundPersistence.store(payload.slot(), payload.bytes())) ClientEvents.flagDoorSoundReload();
        });
    }
}
