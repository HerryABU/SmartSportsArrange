package com.sports.schedule.opt.alns;

import com.sports.schedule.opt.solver.Placement;
import com.sports.schedule.opt.solver.SchedulePlan;
import com.sports.schedule.opt.solver.ScheduleUnit;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 随机修复：每个被拔出的单元随机挑一个放得下的候选位置、按最大档位插回。
 *
 * <p>它存在的理由是<b>多样性</b>：贪心修复每次都做出「局部最优」的插入，
 * 若破坏面相似，修复结果也相似——搜索会在同一个小区域里打转。
 * 随机修复制造差异化的插回方式，给后续轮次的破坏-修复循环提供不同起点。
 * ALNS 框架下它与贪心修复的配比由老虎机按收益自适应调节。</p>
 */
public class RandomRepairOperator implements RepairOperator {

    @Override
    public String name() {
        return "随机修复";
    }

    @Override
    public int repair(SchedulePlan plan, List<ScheduleUnit> removed, Random rnd) {
        int inserted = 0;
        for (ScheduleUnit u : removed) {
            List<int[]> options = new ArrayList<>();   // [placementIdx, duration]
            List<Placement> candidates = u.getCandidatePlacements();
            for (int i = 0; i < candidates.size(); i++) {
                int duration = GreedyRepairOperator.largestFitting(u, candidates.get(i));
                if (duration >= u.getMinDuration()) {
                    options.add(new int[]{i, duration});
                }
            }
            if (options.isEmpty()) continue;
            int[] choice = options.get(rnd.nextInt(options.size()));
            u.setPlacement(candidates.get(choice[0]));
            u.setDuration(choice[1]);
            inserted++;
        }
        return inserted;
    }
}
