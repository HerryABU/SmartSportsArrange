package com.sports.service.schedule;

import com.sports.entity.protection.AdminTimeProtection;
import com.sports.entity.venue.Venue;
import com.sports.schedule.core.primitive.Pool;
import com.sports.schedule.core.primitive.Unit;
import com.sports.schedule.core.primitive.Window;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 编排「准备阶段」的产出——由 {@link AutoSchedulePreparer} 填充、{@code ScheduleService.autoSchedule} 消费。
 *
 * <p>把原 autoSchedule 里 190 行内联的准备逻辑拆出去之后，相位之间需要一个<b>显式的数据契约</b>：
 * 「准备阶段算出什么、后续相位能用什么」看这一个类即知，不必再在几百行的流水线里翻找局部变量；
 * 准备阶段也因而不依赖 facade，可以单独测。</p>
 *
 * <p>字段为 public 可变：它本质是「一次性填充、随后只读」的相位产物，为 31 个字段写
 * getter/setter 只会增加噪声且没有收益。<b>写入方只有 {@link AutoSchedulePreparer} 一个</b>。</p>
 */
public class AutoScheduleContext {

    // ==================== 配置与基础参数 ====================

    /** 合并后的编排配置（已并入 max_attempts、已回写自动推算的 days / dayConfigs） */
    public Map<String, Object> cfg;
    /** 年级顺序（取自 gradeOrder，缺省按 grades 的 sortOrder 升序） */
    public List<String> gradeOrder;
    /** 径赛并发位数（1 = 串行；n = 同一时刻并行 n 个项目） */
    public int trackSlots;
    /** 田赛并发位数 */
    public int fieldSlots;
    public int defaultDuration;
    public int defaultInterval;
    public int heatMinutes;
    public int fieldPerAthlete;
    /** 项目间隔下限（B05/U07：避免项目紧贴导致现场不可行） */
    public int minInterval;
    /** 压缩告警阈值：压缩到「预计用时的 1/ratio 以下」视为严重压缩 */
    public double compressionWarnRatio;
    /** 项目间间隔（放置与可行性预检的统一口径），= max(默认间隔, 间隔下限) */
    public int unitInterval;

    // ==================== 场地与并发池 ====================

    /** 场地原始列表（数据库场地表优先，为空时回退配置 JSON 的 venues） */
    public List<Map<String, Object>> venueList;
    /** 场地名列表（顺序即轮询顺序） */
    public List<String> venues;
    public Map<String, String> codeToName;
    public Map<String, Integer> codeToParallelMax;
    public List<Map<String, Object>> trackTypeVenues;
    public List<Map<String, Object>> fieldTypeVenues;
    /** 场地是否带类型信息（无类型时回退「首个=主场地、其余=田赛」的旧规则） */
    public boolean hasType;
    public String mainVenue;
    public String mainVenueCode;
    public List<String> fieldVenues;
    public Set<String> fieldVenueCodes;
    public Pool trackPool;
    public Pool fieldPool;
    /** 项目级指定场地时按需创建的独立并发池（key = 场地编码） */
    public Map<String, Pool> dedicatedPools;
    /** 场地相关前置告警（与放置顺序无关，最终并入 warnings） */
    public List<String> venueWarnings;

    // ==================== 单元、时间窗与约束 ====================

    public List<Long> eventOrder;
    /** 项目 id → 并行组成员（含捆绑组 / 合作组） */
    public Map<Long, String> event2Group;
    public List<Unit> units;
    /** 时间三态：days &lt; 0 = 尽可能减少（自动推算 + 放大跨天惩罚） */
    public boolean minimizeDays;
    /** 空时间限制：days 缺省或为 0 时按每日时段容量自动推算天数 */
    public boolean autoDays;
    /** 自动推算出的比赛天数（0 = 显式指定天数，未启用自动推算） */
    public int estimatedDays;
    public List<Window> windows;
    /** 全校避让时段（GLOBAL） */
    public List<AdminTimeProtection> globalBlocks;
    /** 项目 id → 受保护区间 {day(-1=全天), startMin, endMin}（TEACHER 个人时段） */
    public Map<Long, List<int[]>> eventBlocked;
    /** 时间窗容量可行性预检（含缺口量化与等比压缩比例） */
    public Map<String, Object> feasibility;
}
