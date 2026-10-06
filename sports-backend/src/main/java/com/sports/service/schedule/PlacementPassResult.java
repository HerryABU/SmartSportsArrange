package com.sports.service.schedule;

import com.sports.entity.event.EventSchedule;

import java.util.ArrayList;
import java.util.List;

/** 单趟放置结果：落库的赛程行 + 冲突统计 + 道次编排计数 + 放置相关告警
 *
 * <p>为什么值得单独一个文件：它是「多起点自适应重试」这条链路的<b>跨趟比较单位</b> ——
 * {@code MultiStartPlacementStrategy.passIsBetter} 正是按这里的三个字段排序择优的。
 * 字段可变（一趟之内要持续累加）所以是 class 而非 record，但**只在包内可见**：
 * 它描述的是本包内部的中间状态，不该外泄成 API。</p>
 */
class PlacementPassResult {

    final List<EventSchedule> saved = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();
    final List<String> autoArrangeFails = new ArrayList<>();
    final int[] conflictStat = {0, 0};
    int autoArrangeOk = 0;
}
