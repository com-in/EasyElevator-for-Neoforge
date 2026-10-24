package org.DJB.easyelevator;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.DJB.easyelevator.block.ElevatorRailBlock;
import org.DJB.easyelevator.block.LandingDoorBlock;
import org.DJB.easyelevator.block.LandingDoorBlockEntity;
import org.DJB.easyelevator.entity.*;
import org.DJB.easyelevator.item.CabinItem;
import org.DJB.easyelevator.screen.CargoScreenHandler;

/**
 * 模组主入口：集中持有全部注册单例，对象的<b>创建与登记</b>都由 {@link ModRegistrar} 在注册表事件中完成。
 *
 * <p>在整体架构中的位置：本类只负责"创建对象并暴露全局单例"，不含任何电梯逻辑；
 * 运行逻辑由 {@link org.DJB.easyelevator.logic.ElevatorController}（纯 Java 状态机）与
 * {@link org.DJB.easyelevator.entity.AbstractCabinEntity}（世界适配层，四个型号的父类）承担。</p>
 *
 * <p>游戏内组件：电梯轨道 {@link #RAIL}、楼层电梯门 {@link #LANDING_DOOR}（注册 ID 仍为
 * {@code easyelevator:call_button}），以及四种共用父类 {@link AbstractCabinEntity} 的轿厢——
 * 普通 {@link #CABIN} / {@link #CABIN_ITEM}（限载 {@link org.DJB.easyelevator.logic.ElevatorParameters#PASSENGER_NUM_LIMIT} 人）、
 * 高速 {@link #HIGH_SPEED_CABIN} / {@link #HIGH_SPEED_CABIN_ITEM}（速度 2.5 倍，外观与普通逐面相同）、
 * 观光 {@link #OBSERVATION_CABIN} / {@link #OBSERVATION_CABIN_ITEM}（四面玻璃，性能不变）、
 * 高速观光 {@link #HIGH_SPEED_OBSERVATION_CABIN} / {@link #HIGH_SPEED_OBSERVATION_CABIN_ITEM}
 * （观光的玻璃外观 + 高速的速度，其余照抄两个来源型号）、
 * 重载 {@link #POWERFUL_CABIN} / {@link #POWERFUL_CABIN_ITEM}（速度 2/3、27 格货舱、限载随货量在 1..20 之间变化）。
 * 五种轿厢的实体 ID 与物品 ID 一一对应；只有重载型号额外注册一个容器菜单 {@link #CARGO_SCREEN}。</p>
 *
 * <p><b>与 Fabric 版的差异（关键）</b>：Fabric 版在 {@code ModInitializer#onInitialize} 里直接
 * {@code Registry.register}，且单例写在静态字段初始化里也能跑——因为 Fabric 的注册表随时可写。
 * NeoForge 不同：原版注册表在模组构造阶段<b>已经冻结</b>，而 {@code Block} / {@code Item} /
 * {@code EntityType} / {@code BlockEntityType} 的构造器都会调
 * {@code createIntrusiveHolder} 向注册表写入，一写就会抛
 * {@code IllegalStateException: Registry is already frozen}。
 * 因此这里<b>不能</b>用静态字段初始化 {@code = new X(...)}（{@code <clinit>} 会在
 * {@code constructMod} 阶段触发，正是注册表冻结的时段），必须把"造对象"这一步也搬进
 * 各自注册表的 {@link RegisterEvent}——只有那段时间注册表是解冻的。
 *
 * <p>所以下面的单例都是<b>非 final 的静态字段</b>，由 {@link ModRegistrar} 在对应注册表事件里赋值。
 * 全模组对它们的引用都在方法体内（运行时才读），没有任何静态初始化捕获，
 * 因此"注册完成后才非空"这件事不会造成问题。</p>
 *
 * <p>注册顺序约束：注册 ID 一经发布不可更改，否则旧存档（方块状态、实体、物品 NBT）与旧物品会全部失效。
 * 跨注册表的创建依赖：{@code BLOCK} 早于 {@code ITEM}（BlockItem 需要方块）与
 * {@code BLOCK_ENTITY_TYPE}（{@link #LANDING_DOOR_BE} 需要 {@link #LANDING_DOOR}），
 * 这是原版注册表的固定顺序，NeoForge 依此依次触发事件。</p>
 */
@Mod(Easyelevator.MOD_ID)
public class Easyelevator {
    /** 模组 ID，同时是全部注册 ID 的命名空间；与 neoforge.mods.toml 中的 modId 必须一致。 */
    public static final String MOD_ID = "easyelevator";

    // ------------------------------------------------------------------
    // 以下全部单例由 ModRegistrar 在 RegisterEvent 中赋值（非 final 是刻意的）。
    // 绝不能改回 "= new X(...)" 的静态初始化写法，理由见类注释。
    // ------------------------------------------------------------------

    /**
     * 重载轿厢货舱的容器菜单类型（注册 ID {@code easyelevator:cargo}），在 {@code MENU} 注册表事件中创建。
     *
     * <p>整个模组唯一的 {@link MenuType}：普通 / 高速 / 观光型号没有容器，选中/门设置等
     * 面板都是纯客户端屏幕（不改物品栏、不与服务端做槽位事务），只有货舱需要原版的容器同步——
     * 27 格物品、拖放分配、多人同时装卸、光标物品回收全部由原版事务保证，因此这里老老实实注册一个菜单。</p>
     *
     * <p>{@link FeatureFlags#VANILLA_SET} 表示本菜单不依赖任何实验性世界开关
     * （它只用原版物品与槽位能力），因此在普通存档里也能开。
     * 类型工厂指向 {@code CargoScreenHandler} 的<b>两参构造器</b>（客户端空壳，见该类的类注释）：
     * 服务端实例由 {@code PowerfulCabinEntity.interact} 直接 new 出来并绑定轿厢。</p>
     */
    public static MenuType<CargoScreenHandler> CARGO_SCREEN;

    /** 电梯轨道方块（{@code BLOCK} 事件创建）：垂直放置的一列连续轨道，朝向即轿厢所在方向。 */
    public static Block RAIL;
    /** 楼层电梯门方块（{@code BLOCK} 事件创建）：唯一根方块（COLUMN=1, LEVEL=0）是站点与控制器，注册 ID 复用旧的 call_button。 */
    public static Block LANDING_DOOR;

    /**
     * 楼层电梯门的方块实体类型（注册 ID {@code easyelevator:landing_door}），在
     * {@code BLOCK_ENTITY_TYPE} 事件中创建（依赖已创建的 {@link #LANDING_DOOR}）。
     *
     * <p>整扇 3x3 门只有根方块会创建它（其余部件 {@code newBlockEntity} 返回 null），
     * 里面存的是门扇滑动的连续进度样本，供渲染插值与碰撞形状使用，详见 {@link LandingDoorBlockEntity}。</p>
     *
     * <p>datafixer 类型参数传 {@code null}：本方块实体没有需要数据迁移的旧存档字段，
     * 与 Fabric 版使用 {@code FabricBlockEntityTypeBuilder}（内部同样走 {@code build(null)}）等价。</p>
     */
    public static BlockEntityType<LandingDoorBlockEntity> LANDING_DOOR_BE;

    /**
     * 普通电梯轿厢实体类型（注册 ID {@code easyelevator:cabin}），{@code ENTITY_TYPE} 事件中创建。
     *
     * <p>碰撞箱固定 3x3（宽 3.0 格、高 3.0 格）；{@code clientTrackingRange(10)} 单位为区块，
     * 即 10 * 16 = 160 格；{@code updateInterval(1)} 表示每刻都向追踪者同步位置，
     * 这是必需的——轿厢以 0.20 格/刻运行，间隔追踪会明显抖动。</p>
     */
    public static EntityType<CabinEntity> CABIN;
    /**
     * 高速电梯轿厢实体类型（注册 ID {@code easyelevator:high_speed_cabin}）。
     *
     * <p>与 {@link #CABIN} 逐项相同，只是实体类在构造时把速度设为 {@link
     * org.DJB.easyelevator.logic.ElevatorParameters#HIGH_SPEED}（2.5 倍 = 10 格/秒）；
     * 尺寸、追踪范围、渲染外观与普通轿厢完全一致，因此旧建筑与井道无需任何改动。</p>
     */
    public static EntityType<HighSpeedCabinEntity> HIGH_SPEED_CABIN;
    /**
     * 观光电梯轿厢实体类型（注册 ID {@code easyelevator:observation_cabin}）。
     *
     * <p>碰撞与追踪参数与 {@link #CABIN} 相同（性能一致），差别只在客户端渲染：四面墙与门扇
     * 用半透明玻璃材质绘制，保留四个角柱、地板与顶板。</p>
     */
    public static EntityType<ObservationCabinEntity> OBSERVATION_CABIN;
    /**
     * 高速观光电梯轿厢实体类型（注册 ID {@code easyelevator:high_speed_observation_cabin}）。
     *
     * <p>碰撞与追踪参数与 {@link #CABIN} 相同，是 {@link #HIGH_SPEED_CABIN}（速度）与
     * {@link #OBSERVATION_CABIN}（外观）的组合：实体类把速度设为
     * {@link org.DJB.easyelevator.logic.ElevatorParameters#HIGH_SPEED}，并让
     * {@code glassWalls()} 返回 true，因此世界里的表现就是"跑得很快的观光轿厢"。
     * 玻璃只改客户端绘制，碰撞外壳照旧按 {@code collisionBoxes()} 生成，井道无需改动。</p>
     */
    public static EntityType<HighSpeedObservationCabinEntity> HIGH_SPEED_OBSERVATION_CABIN;
    /**
     * 重载电梯轿厢实体类型（注册 ID {@code easyelevator:powerful_cabin}）。
     *
     * <p>碰撞与追踪参数与 {@link #CABIN} 完全相同（井道尺寸一致，换型号不需要改建筑），差别有三处：
     * <ul>
     *   <li><b>速度</b>：{@link org.DJB.easyelevator.logic.ElevatorParameters#LOW_SPEED}（普通型号的 2/3 ≈ 2.67 格/秒）——"重载"的代价；</li>
     *   <li><b>限载随货量变化</b>：空舱 {@link org.DJB.easyelevator.logic.ElevatorParameters#HIGH_PASSENGER_NUM_LIMIT} 人，
     *       每 128 件货少载 1 人，满载（1728 件）仍可载 6 人；超过当前上限即进入
     *       {@link org.DJB.easyelevator.logic.ElevatorController.Phase#OVERLOAD}（门保持全开、不派发行程）；</li>
     *   <li><b>27 格货舱</b>：潜行右键打开（{@link #CARGO_SCREEN}），货物随实体存档，
     *       并让后部按货量长出最多 6 个可站可撞的木箱。</li>
     * </ul>
     * 渲染上另有一层"重载"内饰（双扶手、双顶灯、载重铭牌、防滑钢踏板），见
     * {@code PowerfulCabinEntity.heavyDuty()}。</p>
     */
    public static EntityType<PowerfulCabinEntity> POWERFUL_CABIN;

    /**
     * 电梯轿厢生成物品（{@code ITEM} 事件创建）；{@code stacksTo(1)} 限制为一格一个，避免一次放置多台轿厢。
     *
     * <p><b>五个</b>物品只在"生成哪一种轿厢 / 回收哪一件"上不同，逻辑共用 {@link CabinItem}；
     * 实体类型用 Supplier 延迟读取，因此即便 {@code ITEM} 事件早于 {@code ENTITY_TYPE} 事件也无妨。
     * 重载轿厢的物品还多一条 Tooltip 提示（怎么打开货舱），见 {@code CabinItem.appendTooltip}。</p>
     */
    public static Item CABIN_ITEM;
    /** 高速轿厢生成物品（右键轨道生成高速轿厢，外观与普通一致，速度 2.5 倍）。 */
    public static Item HIGH_SPEED_CABIN_ITEM;
    /** 观光轿厢生成物品（右键轨道生成观光轿厢，四面玻璃，性能与普通一致）。 */
    public static Item OBSERVATION_CABIN_ITEM;
    /** 高速观光轿厢生成物品（观光玻璃外观 + 高速速度：0.50 格/刻 = 10 格/秒）。 */
    public static Item HIGH_SPEED_OBSERVATION_CABIN_ITEM;
    /** 重载轿厢生成物品（右键轨道生成重载轿厢，速度 2/3、27 格货舱、限载随货量变化）。 */
    public static Item POWERFUL_CABIN_ITEM;

    /** 运行音效（{@code SOUND_EVENT} 事件创建）：轿厢移动时播放，音量 RUNNING_VOLUME = 0.6f。 */
    public static SoundEvent RUNNING;
    /**
     * 到站提示音（{@code SOUND_EVENT} 事件创建）：轿厢精确到站（误差 &lt;= 1e-7 格）时播放，音量使用 EVENT_VOLUME = 0.8f。
     *
     * <p>这也是每扇门的设置面板里"默认音效"那一项的来源，并且<b>是全模组唯一的门相关音效</b>——
     * 开关门本身不再发声（曾经有 {@code door_open} / {@code door_close} 两个 ID，已随"每扇门只管到站音效"
     * 的改动一并移除）。</p>
     */
    public static SoundEvent ARRIVAL;

    /**
     * 自定义物品栏分组（{@code CREATIVE_MODE_TAB} 事件创建）：图标固定用普通轿厢物品；entries 回调在分组内容被构建时执行，
     * 因此这里只放入"可被玩家直接获得"的物品（轨道、楼层门、四种轿厢），
     * 避免依赖注册顺序或每次打开物品栏都重建列表。
     *
     * <p>位置取 {@code Row.TOP, 7}：原版顶行已占用 0..6，7 是紧随其后的空位，
     * 与 Fabric 版 {@code FabricItemGroup.builder()}（追加到末尾）的观感一致。</p>
     */
    public static CreativeModeTab MAIN_TAB;

    /**
     * 造一台轿厢的实体类型（碰撞箱、追踪参数四种型号完全一致）。
     *
     * <p>{@code build(id)} 只造对象、不注册（注册由 {@link ModRegistrar} 完成）；NeoForge 运行时
     * 不会像原版开发环境那样因查不到 datafixer choice type 而报错，因此直接传注册 ID 即可。
     * 必须在 {@code ENTITY_TYPE} 注册表事件中调用。</p>
     *
     * @param factory 实体构造工厂，例如 {@code CabinEntity::new}
     * @param id 注册 ID（不含命名空间）
     * @param <T> 轿厢实体类型
     * @return 尚未注册的实体类型
     */
    private static <T extends Entity> EntityType<T> cabinType(EntityType.EntityFactory<T> factory, String id) {
        return EntityType.Builder.of(factory, MobCategory.MISC)
                .sized(3.0f, 3.0f).clientTrackingRange(10).updateInterval(1).build(id);
    }

    /**
     * 构造模组主入口。NeoForge 的注册全部走注册表事件（{@link ModRegistrar}），
     * 网络负载注册走 {@code RegisterPayloadHandlersEvent}（{@code ElevatorNetworking}），
     * 因此构造器本身不做任何事——保留它只是为了显式记录这一约定。
     */
    public Easyelevator() {
    }

    /**
     * 构造本模组命名空间下的 {@link ResourceLocation}。
     *
     * @param path 注册路径，不含命名空间（例如 {@code "cabin"}）
     * @return {@code easyelevator:<path>} 形式的标识符
     */
    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    /**
     * 注册表事件订阅：在各自注册表事件里<b>创建对象并登记</b>（NeoForge 替代 Fabric 的 {@code onInitialize} 直接注册）。
     *
     * <p>顺序语义与 Fabric 版一致：方块 -> 物品（BlockItem 与方块同名成对）-> 轿厢物品 -> 物品栏分组；
     * 实体类型、方块实体类型、菜单类型、音效同理。方块与方块物品分属 BLOCK / ITEM 两个注册表，
     * NeoForge 按注册表依次触发事件，顺序天然正确。</p>
     *
     * <p><b>为什么必须在这里 new</b>：只有本事件执行期间对应注册表处于解冻状态，
     * 而 {@code Block}/{@code Item}/{@code EntityType}/{@code BlockEntityType} 的构造器都会
     * 向注册表 {@code createIntrusiveHolder}；放到静态初始化里会在
     * {@code Registry is already frozen} 上直接崩掉。</p>
     */
    @EventBusSubscriber(modid = MOD_ID, bus = EventBusSubscriber.Bus.MOD)
    public static final class ModRegistrar {
        private ModRegistrar() { }

        @SubscribeEvent
        public static void onRegister(RegisterEvent event) {
            Registry<?> registry = event.getRegistry();
            if (registry == BuiltInRegistries.BLOCK) {
                RAIL = new ElevatorRailBlock(BlockBehaviour.Properties.of().strength(3.0f).noOcclusion());
                LANDING_DOOR = new LandingDoorBlock(BlockBehaviour.Properties.of().strength(3.0f).noOcclusion().dynamicShape());
                // Keep the old registry ID so existing inventory items are not lost on upgrade.
                // 兼容性 hack：楼层电梯门沿用旧的 call_button ID（现在显示为"电梯门"），
                // 使旧存档中的方块状态、旧物品堆以及旧配方/掉落表在升级后继续有效。
                event.register(BuiltInRegistries.BLOCK.key(), id("elevator_rail"), () -> RAIL);
                event.register(BuiltInRegistries.BLOCK.key(), id("call_button"), () -> LANDING_DOOR);
            } else if (registry == BuiltInRegistries.ITEM) {
                CABIN_ITEM = new CabinItem(new Item.Properties().stacksTo(1), () -> CABIN);
                HIGH_SPEED_CABIN_ITEM = new CabinItem(new Item.Properties().stacksTo(1), () -> HIGH_SPEED_CABIN);
                OBSERVATION_CABIN_ITEM = new CabinItem(new Item.Properties().stacksTo(1), () -> OBSERVATION_CABIN);
                HIGH_SPEED_OBSERVATION_CABIN_ITEM = new CabinItem(new Item.Properties().stacksTo(1), () -> HIGH_SPEED_OBSERVATION_CABIN);
                POWERFUL_CABIN_ITEM = new CabinItem(new Item.Properties().stacksTo(1), () -> POWERFUL_CABIN);
                event.register(BuiltInRegistries.ITEM.key(), id("elevator_rail"),
                        () -> new BlockItem(RAIL, new Item.Properties()));
                event.register(BuiltInRegistries.ITEM.key(), id("call_button"),
                        () -> new BlockItem(LANDING_DOOR, new Item.Properties()));
                event.register(BuiltInRegistries.ITEM.key(), id("cabin"), () -> CABIN_ITEM);
                event.register(BuiltInRegistries.ITEM.key(), id("high_speed_cabin"), () -> HIGH_SPEED_CABIN_ITEM);
                event.register(BuiltInRegistries.ITEM.key(), id("observation_cabin"), () -> OBSERVATION_CABIN_ITEM);
                event.register(BuiltInRegistries.ITEM.key(), id("high_speed_observation_cabin"), () -> HIGH_SPEED_OBSERVATION_CABIN_ITEM);
                event.register(BuiltInRegistries.ITEM.key(), id("powerful_cabin"), () -> POWERFUL_CABIN_ITEM);
            } else if (registry == BuiltInRegistries.ENTITY_TYPE) {
                CABIN = cabinType(CabinEntity::new, "cabin");
                HIGH_SPEED_CABIN = cabinType(HighSpeedCabinEntity::new, "high_speed_cabin");
                OBSERVATION_CABIN = cabinType(ObservationCabinEntity::new, "observation_cabin");
                HIGH_SPEED_OBSERVATION_CABIN = cabinType(HighSpeedObservationCabinEntity::new, "high_speed_observation_cabin");
                POWERFUL_CABIN = cabinType(PowerfulCabinEntity::new, "powerful_cabin");
                event.register(BuiltInRegistries.ENTITY_TYPE.key(), id("cabin"), () -> CABIN);
                event.register(BuiltInRegistries.ENTITY_TYPE.key(), id("high_speed_cabin"), () -> HIGH_SPEED_CABIN);
                event.register(BuiltInRegistries.ENTITY_TYPE.key(), id("observation_cabin"), () -> OBSERVATION_CABIN);
                event.register(BuiltInRegistries.ENTITY_TYPE.key(), id("high_speed_observation_cabin"), () -> HIGH_SPEED_OBSERVATION_CABIN);
                event.register(BuiltInRegistries.ENTITY_TYPE.key(), id("powerful_cabin"), () -> POWERFUL_CABIN);
            } else if (registry == BuiltInRegistries.BLOCK_ENTITY_TYPE) {
                LANDING_DOOR_BE = BlockEntityType.Builder.of(LandingDoorBlockEntity::new, LANDING_DOOR).build(null);
                event.register(BuiltInRegistries.BLOCK_ENTITY_TYPE.key(), id("landing_door"), () -> LANDING_DOOR_BE);
            } else if (registry == BuiltInRegistries.MENU) {
                CARGO_SCREEN = new MenuType<>(CargoScreenHandler::new, FeatureFlags.VANILLA_SET);
                event.register(BuiltInRegistries.MENU.key(), id("cargo"), () -> CARGO_SCREEN);
            } else if (registry == BuiltInRegistries.SOUND_EVENT) {
                RUNNING = SoundEvent.createVariableRangeEvent(id("elevator_running"));
                ARRIVAL = SoundEvent.createVariableRangeEvent(id("elevator_arrival"));
                event.register(BuiltInRegistries.SOUND_EVENT.key(), id("elevator_running"), () -> RUNNING);
                event.register(BuiltInRegistries.SOUND_EVENT.key(), id("elevator_arrival"), () -> ARRIVAL);
            } else if (registry == BuiltInRegistries.CREATIVE_MODE_TAB) {
                MAIN_TAB = CreativeModeTab.builder(CreativeModeTab.Row.TOP, 7)
                        .title(Component.translatable("itemGroup.easyelevator"))
                        .icon(() -> new ItemStack(CABIN_ITEM))
                        .displayItems((params, output) -> {
                            output.accept(RAIL);
                            output.accept(LANDING_DOOR);
                            output.accept(CABIN_ITEM);
                            output.accept(HIGH_SPEED_CABIN_ITEM);
                            output.accept(OBSERVATION_CABIN_ITEM);
                            output.accept(HIGH_SPEED_OBSERVATION_CABIN_ITEM);
                            output.accept(POWERFUL_CABIN_ITEM);
                        })
                        .build();
                event.register(BuiltInRegistries.CREATIVE_MODE_TAB.key(), id("main"), () -> MAIN_TAB);
            }
        }
    }
}
