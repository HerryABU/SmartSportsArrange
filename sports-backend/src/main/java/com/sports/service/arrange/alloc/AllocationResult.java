package com.sports.service.arrange.alloc;

import com.sports.entity.arrange.Arrangement;

import java.util.List;

/**
 * 入组结果：组数 + 「组 → 单元列表」矩阵 + 编排过程告警。
 *
 * <p>⚠️ 本类的前身是 {@code ArrangementService} 的<b>私有内部类</b> {@code Placement}，
 * 而该名字<b>遮蔽</b>了同名的 {@code com.sports.schedule.opt.solver.Placement}
 * （求解器的「可放置位置」事实类）。于是那行 import 一直是死代码，而读者看到
 * {@code Placement} 会以为在说求解器的事实对象 —— 两个完全不同的概念被同一名字占据。
 * 抽出时改名 {@code AllocationResult}，顺手消灭这处遮蔽。</p>
 *
 * @param heats       组数
 * @param heatsMatrix 组 → 单元列表（元素可能 lane=null，表示待分道）
 * @param warnings    编排过程告警（软约束未满足等），如实上报前端，不吞掉
 */
public record AllocationResult(int heats, List<List<Arrangement>> heatsMatrix,
                               List<String> warnings) {
}
