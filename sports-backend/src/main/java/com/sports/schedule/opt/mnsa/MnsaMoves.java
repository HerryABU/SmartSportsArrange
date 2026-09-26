package com.sports.schedule.opt.mnsa;

import com.sports.schedule.opt.solver.Placement;
import com.sports.schedule.opt.solver.ScheduleConstraintProvider;
import com.sports.schedule.opt.solver.SchedulePlan;
import com.sports.schedule.opt.solver.ScheduleUnit;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * MNSA 的<b>六种标准邻域移动</b>——参照 ITC2021 排名第二的多邻域模拟退火方案
 * （六邻域自适应切换）映射到「项目×年级 → 并发位×起点×时长」这一领域：
 *
 * <ul>
 *   <li><b>同池换位</b>：两个已排单元互换落位——同一并发池内的资源置换；</li>
 *   <li><b>迁移</b>：一个单元挪到候选里的另一个位置——最朴素的局部移动；</li>
 *   <li><b>互换时长</b>：两个单元交换时长——用「一处富余」补「另一处紧张」；</li>
 *   <li><b>压缩时长</b>：单个单元时长降一档——为同窗口的其他项目腾出空间；</li>
 *   <li><b>拔除冲突</b>：兼项撞车的两个单元随机拔掉一个——直接攻击中等层冲突；</li>
 *   <li><b>补排空缺</b>：把未排入的单元塞回某个可容纳的候选位置——救回被丢掉的项目。</li>
 * </ul>
 *
 * <p>全部移动只从实体自己的候选值域里取值（位置 ∈ candidatePlacements、
 * 时长 ∈ durationChoices 且 ≤ 位置余量），因此任何一次移动产生的都是<b>语义合法</b>的解，
 * 质量好坏交给评分器与接受准则判断——「合法性靠值域、优劣靠评分」。</p>
 */
public final class MnsaMoves {

    private MnsaMoves() {
    }

    /** 标准六邻域（顺序即老虎机臂序，确定性） */
    public static List<MnsaMove> standard() {
        return List.of(swapPlacements(), relocate(), swapDurations(),
                shrinkDuration(), evictClashing(), placeUnassigned());
    }

    // ==================== 六种移动 ====================

    /** ① 同池换位：两个已排单元互换落位 */
    public static MnsaMove swapPlacements() {
        return new MnsaMove() {
            @Override
            public String name() {
                return "同池换位";
            }

            @Override
            public boolean apply(SchedulePlan work, Random rnd) {
                List<ScheduleUnit> placed = placedUnits(work);
                if (placed.size() < 2) return false;
                ScheduleUnit a = placed.get(rnd.nextInt(placed.size()));
                // 换位对象只在同池里找（跨池的位置值域不交集，换了必然不合法）
                ScheduleUnit b = null;
                for (int tries = 0; tries < 8 && b == null; tries++) {
                    ScheduleUnit c = placed.get(rnd.nextInt(placed.size()));
                    if (c != a && c.getPoolLabel().equals(a.getPoolLabel())) b = c;
                }
                if (b == null || a.getPlacement().equals(b.getPlacement())) return false;
                // 双重校验：对方的位置必须在自己的候选值域里，且放得下自己的时长
                if (!a.getCandidatePlacements().contains(b.getPlacement())
                        || !b.getCandidatePlacements().contains(a.getPlacement())
                        || !fits(a, b.getPlacement(), a.getDuration())
                        || !fits(b, a.getPlacement(), b.getDuration())) {
                    return false;
                }
                Placement pa = a.getPlacement();
                a.setPlacement(b.getPlacement());
                b.setPlacement(pa);
                return true;
            }
        };
    }

    /** ② 迁移：一个已排单元挪到候选里的另一个位置 */
    public static MnsaMove relocate() {
        return new MnsaMove() {
            @Override
            public String name() {
                return "迁移";
            }

            @Override
            public boolean apply(SchedulePlan work, Random rnd) {
                List<ScheduleUnit> placed = placedUnits(work);
                if (placed.isEmpty()) return false;
                ScheduleUnit u = placed.get(rnd.nextInt(placed.size()));
                List<Placement> options = fittingPlacements(u, u.getDuration());
                options.remove(u.getPlacement());
                if (options.isEmpty()) return false;
                u.setPlacement(options.get(rnd.nextInt(options.size())));
                return true;
            }
        };
    }

    /** ③ 互换时长：两个已排单元交换时长（各自必须放得下对方时长） */
    public static MnsaMove swapDurations() {
        return new MnsaMove() {
            @Override
            public String name() {
                return "互换时长";
            }

            @Override
            public boolean apply(SchedulePlan work, Random rnd) {
                List<ScheduleUnit> placed = placedUnits(work);
                if (placed.size() < 2) return false;
                ScheduleUnit a = placed.get(rnd.nextInt(placed.size()));
                ScheduleUnit b = placed.get(rnd.nextInt(placed.size()));
                if (a == b || a.getDuration().equals(b.getDuration())) return false;
                // 双重校验：对方的时长必须在各自的候选档位里，且各自的落位放得下对方时长
                if (!a.getDurationChoices().contains(b.getDuration())
                        || !b.getDurationChoices().contains(a.getDuration())
                        || !fits(a, a.getPlacement(), b.getDuration())
                        || !fits(b, b.getPlacement(), a.getDuration())) {
                    return false;
                }
                int da = a.getDuration();
                a.setDuration(b.getDuration());
                b.setDuration(da);
                return true;
            }
        };
    }

    /** ④ 压缩时长：单个单元时长在候选档位里降一档（同窗口其他项目因此多出空间） */
    public static MnsaMove shrinkDuration() {
        return new MnsaMove() {
            @Override
            public String name() {
                return "压缩时长";
            }

            @Override
            public boolean apply(SchedulePlan work, Random rnd) {
                List<ScheduleUnit> placed = placedUnits(work);
                if (placed.isEmpty()) return false;
                ScheduleUnit u = placed.get(rnd.nextInt(placed.size()));
                List<Integer> smaller = new ArrayList<>();
                for (int d : u.getDurationChoices()) {
                    if (d < u.getDuration() && fits(u, u.getPlacement(), d)) {
                        smaller.add(d);
                    }
                }
                if (smaller.isEmpty()) return false;
                u.setDuration(smaller.get(rnd.nextInt(smaller.size())));
                return true;
            }
        };
    }

    /** ⑤ 拔除冲突：兼项撞车的两个单元随机拔掉一个（置为未排，允许由后续移动或上游兜底） */
    public static MnsaMove evictClashing() {
        return new MnsaMove() {
            @Override
            public String name() {
                return "拔除冲突";
            }

            @Override
            public boolean apply(SchedulePlan work, Random rnd) {
                List<ScheduleUnit> units = work.getUnits();
                List<int[]> clashPairs = new ArrayList<>();
                for (int i = 0; i < units.size(); i++) {
                    ScheduleUnit a = units.get(i);
                    if (!a.isPlaced() || !a.hasAthletes()) continue;
                    for (int j = i + 1; j < units.size(); j++) {
                        ScheduleUnit b = units.get(j);
                        if (b.isPlaced() && b.hasAthletes()
                                && a.sharesAthlete(b) && ScheduleConstraintProvider.athleteClash(a, b)) {
                            clashPairs.add(new int[]{i, j});
                        }
                    }
                }
                if (clashPairs.isEmpty()) return false;
                int[] pair = clashPairs.get(rnd.nextInt(clashPairs.size()));
                ScheduleUnit victim = units.get(pair[rnd.nextBoolean() ? 0 : 1]);
                victim.setPlacement(null);
                victim.setDuration(null);
                return true;
            }
        };
    }

    /** ⑥ 补排空缺：把一个未排入的单元塞回候选里放得下的位置（优先救回被丢掉的项目） */
    public static MnsaMove placeUnassigned() {
        return new MnsaMove() {
            @Override
            public String name() {
                return "补排空缺";
            }

            @Override
            public boolean apply(SchedulePlan work, Random rnd) {
                List<ScheduleUnit> unplaced = new ArrayList<>();
                for (ScheduleUnit u : work.getUnits()) {
                    if (!u.isPlaced()) unplaced.add(u);
                }
                if (unplaced.isEmpty()) return false;
                ScheduleUnit u = unplaced.get(rnd.nextInt(unplaced.size()));
                // 找候选里能容纳其时长下限的位置，且取放得下的最大档位（尽量少压缩）
                List<Placement> options = new ArrayList<>();
                for (Placement p : u.getCandidatePlacements()) {
                    for (int d : u.getDurationChoices()) {
                        if (d >= u.getMinDuration() && d <= p.getMaxDuration()) {
                            options.add(p);
                            break;
                        }
                    }
                }
                if (options.isEmpty()) return false;
                Placement p = options.get(rnd.nextInt(options.size()));
                u.setPlacement(p);
                u.setDuration(largestFitting(u, p));
                return true;
            }
        };
    }

    // ==================== 共用工具 ====================

    static List<ScheduleUnit> placedUnits(SchedulePlan plan) {
        List<ScheduleUnit> placed = new ArrayList<>();
        for (ScheduleUnit u : plan.getUnits()) {
            if (u.isPlaced()) placed.add(u);
        }
        return placed;
    }

    /** 时长是否能落在该位置上（不超过位置余量即整块放得下） */
    static boolean fits(ScheduleUnit unit, Placement placement, int duration) {
        return duration > 0 && duration <= placement.getMaxDuration();
    }

    /** 该单元在其候选值域内、能放进指定位置的全部位置 */
    static List<Placement> fittingPlacements(ScheduleUnit unit, int duration) {
        List<Placement> options = new ArrayList<>();
        for (Placement p : unit.getCandidatePlacements()) {
            if (fits(unit, p, duration)) options.add(p);
        }
        return options;
    }

    /** 放进指定位置的候选档位中「放得下的最大时长」 */
    static int largestFitting(ScheduleUnit unit, Placement placement) {
        int best = -1;
        for (int d : unit.getDurationChoices()) {
            if (d <= placement.getMaxDuration() && d > best) best = d;
        }
        return best;
    }
}
