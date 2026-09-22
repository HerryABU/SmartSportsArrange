package com.sports.schedule.opt.lns;

import com.sports.schedule.opt.solver.ScheduleUnit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 一个「邻域」= 本次大邻域搜索要**破坏并重建**的那一部分单元。
 *
 * <h2>为什么要主动选邻域，而不是被动找空隙</h2>
 * 原先的「回填式放置」是被动的：游标推到哪里、就在剩下的空隙里塞。它解决不了碎片化——
 * 因为碎片是**前面决策留下的**，而被动策略永远只能接受既成事实。
 *
 * <p>LNS 反过来：<b>主动决定哪些项目要「拔出来重排」</b>。破坏一小块、只重建这一块，
 * 其余保持不动，于是能在有限时间里做很多次局部探索，而且每次探索都真的能改到"根"上。</p>
 *
 * <h2>四种邻域，各治一种病</h2>
 * <ul>
 *   <li><b>按年级</b>：同年级项目共享场地与运动员，跨年级调换常常无效；</li>
 *   <li><b>按运动员</b>：兼项冲突集中在少数"最忙的人"身上，围着他们重排最有效；</li>
 *   <li><b>按时段窗口</b>：碎片与容量浪费都发生在窗口内部；</li>
 *   <li><b>随机子集</b>：防止只盯热点导致视野收窄（长尾问题靠随机扰动才碰得到）。</li>
 * </ul>
 */
public interface Neighborhood {

    String name();

    boolean contains(ScheduleUnit unit);

    /** 该邻域覆盖的单元个数（用于日志与「破坏规模是否合理」的判断） */
    default int size(List<ScheduleUnit> all) {
        int n = 0;
        for (ScheduleUnit u : all) {
            if (contains(u)) n++;
        }
        return n;
    }

    /** 按年级：同一年级的所有项目一起重排 */
    static Neighborhood byGrade(ScheduleUnit anchor) {
        String grade = anchor == null ? null : anchor.getGrade();
        return new Neighborhood() {
            @Override
            public String name() {
                return "按年级(" + (grade == null ? "不分年级" : grade) + ")";
            }

            @Override
            public boolean contains(ScheduleUnit unit) {
                return Objects.equals(grade, unit.getGrade());
            }
        };
    }

    /**
     * 按最忙运动员：把该运动员兼报的所有项目一起重排。
     *
     * <p>找锚点单元里「兼项最多」的那个人——冲突往往就锁在这几个最忙的人身上。</p>
     */
    static Neighborhood byAthlete(ScheduleUnit anchor, List<ScheduleUnit> all) {
        long busiest = -1;
        int most = 0;
        if (anchor != null && anchor.getAthletes() != null) {
            for (long a : anchor.getAthletes()) {
                int n = 0;
                for (ScheduleUnit u : all) {
                    if (u.getAthletes() == null) continue;
                    for (long x : u.getAthletes()) {
                        if (x == a) {
                            n++;
                            break;
                        }
                    }
                }
                if (n > most) {
                    most = n;
                    busiest = a;
                }
            }
        }
        final long target = busiest;
        final int mostCount = most;
        return new Neighborhood() {
            @Override
            public String name() {
                return target < 0 ? "按运动员(无)" : "按最忙运动员#" + target + "(" + mostCount + "项)";
            }

            @Override
            public boolean contains(ScheduleUnit unit) {
                if (target < 0 || unit.getAthletes() == null) return false;
                for (long x : unit.getAthletes()) {
                    if (x == target) return true;
                }
                return false;
            }
        };
    }

    /** 按时段窗口：同一并发位、同一窗口内的项目一起重排（碎片问题在这里） */
    static Neighborhood byBin(ScheduleUnit anchor) {
        String bin = anchor == null || anchor.getPlacement() == null
                ? null : anchor.getPlacement().getBinKey();
        return new Neighborhood() {
            @Override
            public String name() {
                return "按时段窗口(" + bin + ")";
            }

            @Override
            public boolean contains(ScheduleUnit unit) {
                return bin != null && unit.getPlacement() != null
                        && bin.equals(unit.getPlacement().getBinKey());
            }
        };
    }

    /** 随机子集：破坏固定 k 个（含未排入者优先），避免只盯热点 */
    static Neighborhood randomSubset(List<ScheduleUnit> all, int k, long seed) {
        Set<String> picked = new HashSet<>();
        List<ScheduleUnit> unplaced = new ArrayList<>();
        for (ScheduleUnit u : all) {
            if (!u.isPlaced()) unplaced.add(u);
        }
        // 先把「没排进去的」放进邻域，再随机补足 —— 让 LNS 优先去救被丢掉的项目
        for (ScheduleUnit u : unplaced) {
            picked.add(u.getKey());
        }
        List<ScheduleUnit> pool = new ArrayList<>(all);
        java.util.Random rnd = new java.util.Random(seed);
        while (picked.size() < Math.min(k, all.size()) && !pool.isEmpty()) {
            picked.add(pool.remove(rnd.nextInt(pool.size())).getKey());
        }
        return new Neighborhood() {
            @Override
            public String name() {
                return "随机子集(" + picked.size() + "项)";
            }

            @Override
            public boolean contains(ScheduleUnit unit) {
                return picked.contains(unit.getKey());
            }
        };
    }
}
