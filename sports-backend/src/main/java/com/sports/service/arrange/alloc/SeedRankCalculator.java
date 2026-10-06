package com.sports.service.arrange.alloc;

import com.sports.entity.arrange.Arrangement;
import com.sports.entity.athlete.Athlete;
import com.sports.entity.event.Event;
import com.sports.entity.registration.Registration;
import com.sports.entity.result.Result;
import com.sports.repository.registration.RegistrationRepository;
import com.sports.repository.result.ResultRepository;
import com.sports.schedule.ai.LaneAdvisorService;

import java.util.*;

/**
 * 种子名次 / 派遣顺序（2026-10-06 从 {@code ArrangementService} 抽出）。
 *
 * <p>「种子名次」是蛇形与 AI 款型的<b>排序口径</b>：名次 1 = 最先派遣。
 * 两条来源：</p>
 * <ol>
 *   <li>{@link #buildSeedRank} —— 成绩种子（预赛成绩 ∪ 该项目已有有效成绩，取更优）；</li>
 *   <li>{@link #buildAiSeedRank} —— 模型给出的派遣优先级，
 *       <b>模型不可用 / 推理失败 / 依赖未装配时自动回退成绩种子</b>。</li>
 * </ol>
 *
 * <p>为什么必须自动回退：新增能力不得让既有路径变脆 —— 编排在任何情况下都要能出结果。</p>
 *
 * <p>无成绩者不入表 ⇒ 排序时落最后（等效退回报名序），而不是给一个假的「最快」。</p>
 */
public class SeedRankCalculator {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(SeedRankCalculator.class);

    private final ResultRepository resultRepository;
    private final RegistrationRepository registrationRepository;
    private final LaneAdvisorService laneAdvisorService;

    public SeedRankCalculator(ResultRepository resultRepository,
                              RegistrationRepository registrationRepository,
                              LaneAdvisorService laneAdvisorService) {
        this.resultRepository = resultRepository;
        this.registrationRepository = registrationRepository;
        this.laneAdvisorService = laneAdvisorService;
    }

    /**
     * AI 派遣顺序 → 种子名次（1 = 最先派遣）。
     *
     * <p>把「该按什么顺序派遣」交给训练好的模型（见 {@code sports-ai/sports_ai/lane_advisor.py}），
     * 再由既有的蛇形分散逻辑消费 —— <b>只换排序口径，分组/分道实现完全复用</b>，
     * 因此对既有管线零阻抗、可随时开关。</p>
     *
     * <p>模型不可用 / 推理失败 / 依赖未装配（单测）时回退 {@link #buildSeedRank}（成绩种子），
     * 保证道次编排在任何情况下都能出结果。</p>
     */
    public Map<Long, Integer> buildAiSeedRank(Event event, List<Athlete> pool, int lanes) {
        if (laneAdvisorService == null || pool == null || pool.isEmpty()
                || !laneAdvisorService.isAvailable()) {
            return buildSeedRank(event, null);
        }
        int n = pool.size();
        List<String> classes = new ArrayList<>(n);
        List<Long> ids = new ArrayList<>(n);
        boolean[] female = new boolean[n];
        int[] eventCounts = new int[n];
        double[] seed = new double[n];
        for (int i = 0; i < n; i++) {
            Athlete a = pool.get(i);
            ids.add(a.getId());
            classes.add(AthleteKeys.classKeyOf(a));
            String g = a.getGender();
            female[i] = "女".equals(g) || "f".equalsIgnoreCase(g) || "F".equals(g);
            eventCounts[i] = 1;
            seed[i] = 0.0;
        }
        // 报名项目数：批量拉一次，避免 N 次单查（模型用它衡量「兼项多者优先分散」）
        try {
            Map<Long, Integer> cnt = new HashMap<>();
            for (Registration r : registrationRepository.findActiveByAthleteIdIn(ids)) {
                if (r.getAthlete() != null && r.getAthlete().getId() != null) {
                    cnt.merge(r.getAthlete().getId(), 1, Integer::sum);
                }
            }
            for (int i = 0; i < n; i++) {
                eventCounts[i] = Math.max(1, cnt.getOrDefault(ids.get(i), 1));
            }
        } catch (Exception ex) {
            log.warn("AI 派遣：报名项目数查询失败，按 1 处理: {}", ex.toString());
        }

        java.util.Optional<int[]> order =
                laneAdvisorService.suggestOrder(classes, female, eventCounts, seed, lanes);
        if (order.isEmpty()) {
            return buildSeedRank(event, null);
        }
        Map<Long, Integer> rank = new LinkedHashMap<>();
        int[] idx = order.get();
        for (int pos = 0; pos < idx.length; pos++) {
            int ai = idx[pos];
            if (ai >= 0 && ai < n) {
                rank.put(ids.get(ai), pos + 1);
            }
        }
        log.info("道次 AI 派遣: event={}, {} 名运动员按 AI 优先级排序（款型 AI）",
                event.getId(), rank.size());
        return rank;
    }

    /**
     * 构建「种子名次」：运动员 → 名次（1=最快）。成绩来源：① 晋级者的预赛成绩；② 该项目已有有效成绩；
     * 二者取最优（最小时间）。无成绩者不入表（排序时落最后，等效退回报名序）。
     */
    public Map<Long, Integer> buildSeedRank(Event event, List<Arrangement> qualifierRefs) {
        Map<Long, Double> best = new HashMap<>();
        if (qualifierRefs != null) {
            for (Arrangement q : qualifierRefs) {
                if (q.getAthlete() != null && q.getAthlete().getId() != null && q.getPrelimTimeSeconds() != null) {
                    best.merge(q.getAthlete().getId(), q.getPrelimTimeSeconds(), Math::min);
                }
            }
        }
        try {
            for (Result r : resultRepository.findValidByEventId(event.getId())) {
                if (r.getAthlete() != null && r.getAthlete().getId() != null && r.getTimeSeconds() != null) {
                    best.merge(r.getAthlete().getId(), r.getTimeSeconds(), Math::min);
                }
            }
        } catch (Exception ex) {
            log.warn("种子蛇形读取成绩失败（退回报名序）: {}", ex.getMessage());
        }
        List<Map.Entry<Long, Double>> list = new ArrayList<>(best.entrySet());
        list.sort(Map.Entry.comparingByValue());
        Map<Long, Integer> rank = new HashMap<>();
        int r = 1;
        for (Map.Entry<Long, Double> e : list) rank.put(e.getKey(), r++);
        return rank;
    }

}
