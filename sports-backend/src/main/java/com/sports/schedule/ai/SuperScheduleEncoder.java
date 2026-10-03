package com.sports.schedule.ai;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 超级编排异构图编码器：把项目/道次/球类/淘汰赛/二次编排统一编成 8 类约束边的图，
 * 喂给 {@code super_moe.onnx}（一个模型覆盖全部九类编排）。
 *
 * <p>与训练侧 {@code sports_ai/data/super_encode.py} 的 {@code encode_super_graph} 逐位对齐。
 * 边类型顺序即 ONNX 通道号，<b>双端契约</b>：</p>
 * <ol start="0">
 *   <li>{@code ATHLETE} 兼项（共享运动员）—— 权重 = 共享人数 / 全局最大</li>
 *   <li>{@code BLOCK} 项目块（同 group_key）—— <b>禁止见缝插针乱排</b>的关键约束</li>
 *   <li>{@code VENUE} 场地独占</li>
 *   <li>{@code POOL} 同并发池</li>
 *   <li>{@code LANE} 同道次/同批次</li>
 *   <li>{@code TIME} 装箱 + 间隔耦合</li>
 *   <li>{@code BRACKET} 淘汰赛晋级 / 二次编排挂靠</li>
 *   <li>{@code TEAM} 同队</li>
 * </ol>
 *
 * <h3>时间目标三态</h3>
 * <ul>
 *   <li>{@code daysLimit >= 1} 硬约束：必须在 N 天内排完</li>
 *   <li>{@code daysLimit == 0} 不限时间：只要可行</li>
 *   <li>{@code daysLimit == -1} 最小化工期：可行前提下尽量压缩</li>
 * </ul>
 * 归一化后编码进第 12 维（0=不限 / 0.5=硬约束 / 1=最小化），
 * <b>原值保留在 {@link Encoded#daysLimit} 供调用方解释</b>——模型只吃归一化值。
 */
@Component
public class SuperScheduleEncoder {

    public static final int E_ATHLETE = 0;
    public static final int E_BLOCK = 1;
    public static final int E_VENUE = 2;
    public static final int E_POOL = 3;
    public static final int E_LANE = 4;
    public static final int E_TIME = 5;
    public static final int E_BRACKET = 6;
    public static final int E_TEAM = 7;
    public static final int N_EDGES = 8;

    public static final int NODE_FEAT_DIM = 20;
    /** 图级（实例级）特征维数：冲突密度/规模/场地/天数/时间目标/并行度/填充率/块压力。 */
    public static final int GRAPH_FEAT_DIM = 8;
    public static final int N_TASKS = 9;
    public static final int N_FORMATS = 4;
    public static final int MAX_SLOTS = 16;

    /** 邻接 O(N²)，超过则如实降级而不是静默截断。 */
    public static final int MAX_NODES = 512;

    /** 九类编排任务——顺序与 MoE 专家下标一致。 */
    public static final int TASK_PROJECT = 0;
    public static final int TASK_LANE = 1;
    public static final int TASK_BALL = 2;
    public static final int TASK_KNOCKOUT = 3;
    public static final int TASK_BLOCK = 4;
    public static final int TASK_CONFLICT = 5;
    public static final int TASK_CAPACITY = 6;
    public static final int TASK_MAKESPAN = 7;
    public static final int TASK_RESECOND = 8;

    private static final String[] TASK_NAMES = {
            "项目编排", "道次编排", "球类赛制", "淘汰赛晋级", "项目块完整性",
            "兼项避让", "装箱容量", "工期压缩", "二次编排"};

    /** 一个待编排单元。 */
    public record Unit(String key, String name, int task, boolean track, boolean team,
                       Integer format, String groupKey, String venue, String pool,
                       String grade, int duration, int interval, int heatCapacity,
                       List<Long> athletes, Integer bracketRound, String resecondOf,
                       String stage) {

        public Unit {
            if (key == null) {
                key = "";
            }
            if (name == null) {
                name = "";
            }
            if (venue == null || venue.isBlank()) {
                venue = "V0";
            }
            if (pool == null || pool.isBlank()) {
                pool = "P0";
            }
            if (grade == null) {
                grade = "";
            }
            if (stage == null) {
                stage = "main";
            }
            athletes = athletes == null ? List.of() : List.copyOf(athletes);
            duration = Math.max(1, duration);
            interval = Math.max(0, interval);
        }
    }

    /** 可用时段（窗口）。 */
    public record Window(int day, int windowIdx, int capacity, String venue, String pool) {
    }

    /** 编码结果。 */
    public record Encoded(float[][] nodeFeat, float[][][] adjByType, float[] typeMask,
                          float[] mask, float[] graphFeat, int n, int daysLimit,
                          boolean degraded, String degradeReason) {

        public float[][][] nodeFeatBatch() {
            return new float[][][]{nodeFeat};
        }

        public float[][][][] adjBatch() {
            return new float[][][][]{adjByType};
        }

        public float[][] typeMaskBatch() {
            return new float[][]{typeMask};
        }

        public float[][] maskBatch() {
            return new float[][]{mask};
        }
    }

    /**
     * 编码。
     *
     * @param units     待编排单元
     * @param windows   可用时段
     * @param daysLimit 时间目标：{@code >=1} 硬约束 / {@code 0} 不限 / {@code -1} 最小化
     * @param athletes  总人数（用于规模归一）
     */
    public Encoded encode(List<Unit> units, List<Window> windows, int daysLimit, int athletes) {
        int n = units == null ? 0 : units.size();
        float[] mask = new float[Math.max(0, n)];
        java.util.Arrays.fill(mask, 1f);
        if (n == 0) {
            return new Encoded(new float[0][NODE_FEAT_DIM], new float[N_EDGES][0][0],
                    new float[N_EDGES], mask, new float[GRAPH_FEAT_DIM],
                    0, daysLimit, false, null);
        }
        if (n > MAX_NODES) {
            return new Encoded(new float[0][NODE_FEAT_DIM], new float[N_EDGES][0][0],
                    new float[N_EDGES], mask, new float[GRAPH_FEAT_DIM], 0, daysLimit, true,
                    "待编排单元 " + n + " 超过单层上限 " + MAX_NODES + "，已回退规则编排");
        }

        float[][][] adj = new float[N_EDGES][n][n];
        float[] tmask = new float[N_EDGES];

        // ---- E_ATHLETE：兼项 ----
        Map<Long, List<Integer>> byAth = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            for (Long a : units.get(i).athletes()) {
                byAth.computeIfAbsent(a, k -> new ArrayList<>()).add(i);
            }
        }
        Map<String, Integer> shared = new LinkedHashMap<>();
        for (List<Integer> idxs : byAth.values()) {
            for (int x = 0; x < idxs.size(); x++) {
                for (int y = x + 1; y < idxs.size(); y++) {
                    int a = idxs.get(x);
                    int b = idxs.get(y);
                    String k = a <= b ? a + "_" + b : b + "_" + a;
                    shared.merge(k, 1, Integer::sum);
                }
            }
        }
        if (!shared.isEmpty()) {
            int mx = shared.values().stream().mapToInt(Integer::intValue).max().orElse(1);
            for (Map.Entry<String, Integer> e : shared.entrySet()) {
                String[] ab = e.getKey().split("_");
                int a = Integer.parseInt(ab[0]);
                int b = Integer.parseInt(ab[1]);
                float w = (float) e.getValue() / mx;
                adj[E_ATHLETE][a][b] = w;
                adj[E_ATHLETE][b][a] = w;
            }
            tmask[E_ATHLETE] = 1f;
        }

        // ---- 分组建边：项目块 / 场地 / 池 / 同队 ----
        bucket(units, adj, tmask, E_BLOCK, u -> u.groupKey());
        bucket(units, adj, tmask, E_VENUE, u -> u.venue());
        bucket(units, adj, tmask, E_POOL, u -> u.pool());
        // E_TEAM：球类单元按「项目名」分组（同项目的不同轮次算同队）
        boolean anyTeam = units.stream().anyMatch(Unit::team);
        if (anyTeam) {
            bucket(units, adj, tmask, E_TEAM, Unit::name);
        }

        // ---- E_LANE：同道次（同项目同年级且 heatCapacity>0） ----
        Map<String, List<Integer>> lane = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            Unit u = units.get(i);
            if (u.heatCapacity() > 0) {
                // ⚠️ 分组键必须带 venue：不同场地的「同项目同年级」不是同一批次，
                //    只按 name+grade 会把它们错误地连起来。
                lane.computeIfAbsent(u.name() + "|" + u.grade() + "|" + u.venue(),
                        k -> new ArrayList<>()).add(i);
            }
        }
        linkAll(lane, adj, tmask, E_LANE, n);

        // ---- E_BRACKET：晋级关系 + 二次编排挂靠 ----
        Map<String, Integer> byKey = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            byKey.putIfAbsent(units.get(i).key(), i);
        }
        for (int i = 0; i < n; i++) {
            Unit u = units.get(i);
            if (u.task() == TASK_KNOCKOUT) {
                for (int j = 0; j < n; j++) {
                    if (i == j) {
                        continue;
                    }
                    Unit v = units.get(j);
                    if (v.task() == TASK_KNOCKOUT && v.name().equals(u.name())
                            && java.util.Objects.equals(v.bracketRound(), u.bracketRound())) {
                        adj[E_BRACKET][i][j] = adj[E_BRACKET][j][i] = 1f;
                        tmask[E_BRACKET] = 1f;
                    }
                }
            }
            if (u.resecondOf() != null && !u.resecondOf().isBlank()) {
                Integer j = byKey.get(u.resecondOf());
                if (j != null) {
                    adj[E_BRACKET][i][j] = adj[E_BRACKET][j][i] = 1f;
                    tmask[E_BRACKET] = 1f;
                }
            }
        }

        // ---- E_TIME：装箱 + 间隔 ----
        Map<String, Integer> capByVenue = new LinkedHashMap<>();
        int maxDay = 1;
        if (windows != null) {
            for (Window w : windows) {
                capByVenue.merge(w.venue(), w.capacity(), Math::max);
                maxDay = Math.max(maxDay, w.day());
            }
        }
        for (int i = 0; i < n; i++) {
            Unit u = units.get(i);
            int cap = Math.max(1, capByVenue.getOrDefault(u.venue(), 1));
            for (int j = i + 1; j < n; j++) {
                Unit v = units.get(j);
                int need = u.duration() + v.duration() + Math.max(u.interval(), v.interval());
                if (need > cap) {
                    float w = Math.min(1f, (float) need / cap);
                    if (w > adj[E_TIME][i][j]) {
                        adj[E_TIME][i][j] = w;
                        adj[E_TIME][j][i] = w;
                    }
                }
            }
            tmask[E_TIME] = 1f;
        }

        // ---- 20 维节点特征 ----
        float[][] feat = new float[n][NODE_FEAT_DIM];
        double totalDur = units.stream().mapToInt(Unit::duration).sum();
        if (totalDur <= 0) {
            totalDur = 1;
        }
        int maxPeople = Math.max(1, units.stream()
                .mapToInt(u -> u.athletes().size()).max().orElse(1));
        int nVenues = Math.max(1, (int) units.stream().map(Unit::venue).distinct().count());
        Map<String, Integer> blockSize = new LinkedHashMap<>();
        for (Unit u : units) {
            if (u.groupKey() != null && !u.groupKey().isBlank()) {
                blockSize.merge(u.groupKey(), 1, Integer::sum);
            }
        }
        Map<Long, Integer> athCnt = new LinkedHashMap<>();
        for (Unit u : units) {
            for (Long a : u.athletes()) {
                athCnt.merge(a, 1, Integer::sum);
            }
        }
        float timeGoal = daysLimit >= 1 ? 0.5f : (daysLimit == 0 ? 0.0f : 1.0f);

        for (int i = 0; i < n; i++) {
            Unit u = units.get(i);
            int cap = Math.max(1, capByVenue.getOrDefault(u.venue(), 1));
            int people = u.athletes().size();
            int expoSum = 0;
            for (Long a : u.athletes()) {
                expoSum += athCnt.getOrDefault(a, 1) - 1;
            }
            float expo = people == 0 ? 0f
                    : Math.min(1f, (expoSum / (float) people) / 3f);
            float fmtOh0 = 0f, fmtOh1 = 0f, fmtOh2 = 0f, fmtOh3 = 0f;
            if (u.format() != null && u.format() >= 0 && u.format() < N_FORMATS) {
                switch (u.format()) {
                    case 0 -> fmtOh0 = 1f;
                    case 1 -> fmtOh1 = 1f;
                    case 2 -> fmtOh2 = 1f;
                    default -> fmtOh3 = 1f;
                }
            }
            int unitDay = 1;
            if (windows != null) {
                for (Window w : windows) {
                    if (w.venue().equals(u.venue())) {
                        unitDay = Math.max(unitDay, w.day());
                    }
                }
            }
            float stage = switch (u.stage()) {
                case "prelim" -> 0.33f;
                case "final" -> 0.66f;
                case "resecond" -> 1.0f;
                default -> 0.0f;
            };
            // ⚠️ 恰好 20 项（0..19）。改这个数组必须同步
            //    sports-ai/sports_ai/data/super_encode.py 的 feat[i]，
            //    以及 super_moe.onnx 的输入维度，否则会静默错位。
            feat[i] = new float[]{
                    n / 128f,                                   // 0 单元数占比
                    Math.min(people, 512) / 512f,                // 1 人数
                    people / (float) maxPeople,                 // 2 相对人数
                    (float) u.duration() / (float) totalDur,   // 3 时长占比
                    1f / nVenues,                              // 4 场地
                    Math.min(1f, (u.duration() + u.interval()) / (float) cap),  // 5 装箱紧张度
                    Math.max(0f, 1f - (u.duration() + u.interval()) / (float) cap),  // 6 容量余量
                    expo,                                        // 7 冲突暴露
                    people == 0 ? 0f : Math.min(1f, (expoSum / (float) people) / 3f),  // 8 兼项占比
                    (u.groupKey() != null && !u.groupKey().isBlank()) ? 1f : 0f,       // 9 是否项目块
                    Math.min(1f, blockSize.getOrDefault(
                            u.groupKey() == null ? "" : u.groupKey(), 0) / 8f),      // 10 块内规模
                    u.heatCapacity() > 0 ? 1f : 0f,               // 11 是否有道次
                    u.heatCapacity() > 0 ? Math.min(1f, u.heatCapacity() / 8f) : 0f,   // 12 heat 容量
                    timeGoal,                                     // 13 时间目标三态
                    Math.min(1f, unitDay / (float) maxDay),       // 14 天数占比
                    stage,                                        // 15 stage 编码
                    fmtOh0, fmtOh1, fmtOh2, fmtOh3,               // 16-19 赛制 onehot
            };
        }
        // ---- 图级（实例级）特征：路由器据此判断「这场赛会该派哪位专家」 ----
        // ⚠️ 八维必须与 sports_ai/data/super_encode.py 的 graph_feat 逐位对齐，
        //    它是路由器的「问题结构」输入（冲突面多广、几天、时间目标、并行度…）。
        double confSum = 0d;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                confSum += adj[E_ATHLETE][i][j];
            }
        }
        double confDensity = confSum / Math.max(1d, (double) n * n);
        int maxParallel = 1;
        int totalCapacity = 0;
        if (windows != null) {
            Map<String, Integer> perSlot = new LinkedHashMap<>();
            for (Window w : windows) {
                perSlot.merge(w.day() + "#" + w.windowIdx(), 1, Integer::sum);
                totalCapacity += w.capacity();
            }
            for (Integer c : perSlot.values()) {
                maxParallel = Math.max(maxParallel, c);
            }
        }
        int demand = 0;
        for (Unit u : units) {
            demand += u.duration() + u.interval();
        }
        int blockCount = blockSize.size();
        float[] graphFeat = new float[]{
                (float) Math.min(1d, confDensity * 8d),                        // 0 冲突密度
                Math.min(1f, n / 128f),                                        // 1 单元规模
                Math.min(1f, nVenues / 12f),                                   // 2 场地数
                Math.min(1f, maxDay / 7f),                                     // 3 天数
                timeGoal,                                                      // 4 时间目标（0/0.5/1）
                Math.min(1f, maxParallel / 4f),                                // 5 每时段并行场地数
                Math.min(1f, demand / Math.max(1, totalCapacity)),             // 6 填充率
                Math.min(1f, blockCount / (float) Math.max(1, maxDay) / 4f),  // 7 块压力
        };

        return new Encoded(feat, adj, tmask, mask, graphFeat, n, daysLimit, false, null);
    }

    // ------------------------------------------------------------------
    private void bucket(List<Unit> units, float[][][] adj, float[] tmask, int type,
                        java.util.function.Function<Unit, String> keyFn) {
        Map<String, List<Integer>> b = new LinkedHashMap<>();
        for (int i = 0; i < units.size(); i++) {
            String k = keyFn.apply(units.get(i));
            if (k != null && !k.isBlank()) {
                b.computeIfAbsent(k, x -> new ArrayList<>()).add(i);
            }
        }
        linkAll(b, adj, tmask, type, units.size());
    }

    private void linkAll(Map<String, List<Integer>> b, float[][][] adj, float[] tmask,
                         int type, int n) {
        for (List<Integer> idxs : b.values()) {
            if (idxs.size() < 2) {
                continue;
            }
            float press = Math.min(1f, (float) idxs.size() / n);
            for (int x = 0; x < idxs.size(); x++) {
                for (int y = x + 1; y < idxs.size(); y++) {
                    int a = idxs.get(x);
                    int c = idxs.get(y);
                    float w = Math.max(adj[type][a][c], press);
                    adj[type][a][c] = w;
                    adj[type][c][a] = w;
                }
            }
            tmask[type] = 1f;
        }
    }

    /** 九类任务的名称（供 /api 上报与前端展示）。 */
    public static String[] taskNames() {
        return TASK_NAMES.clone();
    }
}
