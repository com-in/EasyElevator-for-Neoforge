package org.DJB.easyelevator.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import org.DJB.easyelevator.Easyelevator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端模组初始化（模组总线，仅客户端 dist）：渲染器注册、界面绑定与一次性启动逻辑。
 *
 * <p>在整体架构中的位置：对应 Fabric 版 {@code EasyelevatorClient#onInitializeClient} 的前半部分
 * （渲染器与 {@code HandledScreens.register}）。NeoForge 把渲染器与界面绑定拆成两个模组总线事件，
 * S2C 包处理器搬到 {@link ClientPayloadHandlers}（随负载注册一起执行），
 * 每刻回调与断线清理搬到 {@link ClientEvents}（Forge 总线）。</p>
 *
 * <p>客户端只读服务端同步的状态——MotionFrame 自定义包（绝对 double 位置）+ SynchedEntityData
 * （PHASE、DOOR、FACING、TARGET_Y）；移动包的权威校验仍在服务端。</p>
 */
@EventBusSubscriber(modid = Easyelevator.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class EasyelevatorClient {
    /** 客户端入口的日志：启动时记一行"文件对话框走哪条路"，便于排查 headless 相关问题。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("easyelevator/client");

    private EasyelevatorClient() { }

    /**
     * 客户端一次性初始化：文件对话框后端日志、GLFW drop 回调首次挂载。
     *
     * <p>拖拽上传是自定义到站音效最方便的入口——不依赖 AWT，也不依赖 PowerShell 弹窗
     * （见 {@link FileDropHandler}）。这里只是第一次尝试；窗口若还没就绪，
     * {@link ClientEvents} 的刻回调会继续重试（挂上后函数自己就变成空操作）。</p>
     */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // 启动时把"这个进程能不能弹 AWT 窗口"记一行：headless 时门设置面板的"选择文件"会改走系统原生对话框，
        // 出问题时这一行能直接说明走的是哪条路（免去再猜一轮）。
        LOGGER.info("File dialog backend: {}", FilePicker.isHeadless() ? "native (AWT is headless)" : "AWT");
        FileDropHandler.register(net.minecraft.client.Minecraft.getInstance());
    }

    /**
     * 货舱菜单的界面绑定：菜单类型（服务端）在 {@link Easyelevator#CARGO_SCREEN} 注册，
     * "用哪个 Screen 画它"只能在客户端注册（专用服务器不加载任何 Screen 类）。
     */
    @SubscribeEvent
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(Easyelevator.CARGO_SCREEN, CargoScreen::new);
    }

    /**
     * 注册五个轿厢实体渲染器与楼层门方块实体渲染器。
     *
     * <p>五种轿厢（普通 / 高速 / 观光 / 高速观光 / 重载）共用同一个渲染器：泛型参数取共同的父类，
     * 因此每个实体类型各注册一次即可；外观差异（观光型号的玻璃墙、重载型号的重载内饰与双灯补光）
     * 由实体自身的 {@code glassWalls()} / {@code heavyDuty()} 决定，而不是按实体类型分支——
     * 将来加型号时这里只需要多一行注册。</p>
     *
     * <p>楼层门门扇是连续滑动的几何，方块模型做不到逐帧插值，因此交给方块实体渲染器绘制
     * （门框仍由方块模型画）。</p>
     */
    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(Easyelevator.CABIN, CabinRenderer::new);
        event.registerEntityRenderer(Easyelevator.HIGH_SPEED_CABIN, CabinRenderer::new);
        event.registerEntityRenderer(Easyelevator.OBSERVATION_CABIN, CabinRenderer::new);
        event.registerEntityRenderer(Easyelevator.HIGH_SPEED_OBSERVATION_CABIN, CabinRenderer::new);
        event.registerEntityRenderer(Easyelevator.POWERFUL_CABIN, CabinRenderer::new);
        event.registerBlockEntityRenderer(Easyelevator.LANDING_DOOR_BE, LandingDoorRenderer::new);
    }
}
