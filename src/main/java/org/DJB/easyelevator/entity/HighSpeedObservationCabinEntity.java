package org.DJB.easyelevator.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import org.DJB.easyelevator.Easyelevator;
import org.DJB.easyelevator.logic.ElevatorParameters;

/**
 * 高速观光电梯轿厢（注册 ID {@code easyelevator:high_speed_observation_cabin}）。
 *
 * <p>就是"观光型号的玻璃外观"与"高速型号的速度"两件事的组合，除此之外不引入任何新语义：
 * <ul>
 *   <li><b>外观 = 观光</b>：{@link #glassWalls()} 恒为 true，墙面与门扇按半透明玻璃绘制，
 *       与 {@link ObservationCabinEntity} 逐面一致（四个角柱、地板、顶板仍不透明）。</li>
 *   <li><b>速度 = 高速</b>：构造时传 {@link ElevatorParameters#HIGH_SPEED}
 *       （= {@link ElevatorParameters#SPEED} × 2.5 = 0.50 格/刻 = <b>10 格/秒</b>），
 *       与 {@link HighSpeedCabinEntity} 一致。</li>
 * </ul>
 *
 * <p>其余约束照抄两个来源型号：门时序（开关各 {@link ElevatorParameters#DOOR_TICKS} 刻、
 * 停留 {@link ElevatorParameters#DWELL_TICKS} 刻）、到站吸附容限
 * {@link ElevatorParameters#POSITION_EPSILON}、限载
 * {@link ElevatorParameters#PASSENGER_NUM_LIMIT} 人、碰撞箱与井道预留（3x3x3）全部不变。
 * 单刻位移 0.5 格 &lt; 1 格，因此不会跨过整格站点；障碍扫掠按本刻实际位移逐刻检查，
 * 更快的车不会"跳过"一格障碍。
 *
 * <p>渲染上由实体自身的 {@code glassWalls()} 决定分支，不按实体类型判断，
 * 因此 {@code CabinRenderer} 无需改动，只需在 {@code EasyelevatorClient} 多注册一行渲染器。
 */
public class HighSpeedObservationCabinEntity extends AbstractCabinEntity {

    /**
     * 构造高速观光轿厢。
     *
     * @param type 实体类型（由 {@link Easyelevator#HIGH_SPEED_OBSERVATION_CABIN} 注册）
     * @param world 所在世界
     */
    public HighSpeedObservationCabinEntity(EntityType<?> type, Level world) { super(type, world, ElevatorParameters.HIGH_SPEED, ElevatorParameters.PASSENGER_NUM_LIMIT); }

    /** @return 高速观光轿厢物品 {@link Easyelevator#HIGH_SPEED_OBSERVATION_CABIN_ITEM}：回收后仍得到本型号 */
    @Override protected Item cabinItem() { return Easyelevator.HIGH_SPEED_OBSERVATION_CABIN_ITEM; }

    /** @return 恒为 true：渲染时把墙面与门扇画成半透明玻璃（四个角柱、地板、顶板仍不透明） */
    @Override public boolean glassWalls() { return true; }
}
