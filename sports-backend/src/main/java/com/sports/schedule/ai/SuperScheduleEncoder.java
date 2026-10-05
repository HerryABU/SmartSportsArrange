package com.sports.schedule.ai;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    /**
     * 2026-10-05 新增两条**微调专属**边，与既有 8 类语义正交，不可用已有通道顶替。
     *
     * <p>{@link #E_HEAT_STAGGER}：两个单元的<b>某两组</b>在时间上真实撞车
     * （项目行可能并不重叠 —— 这正是「组次错开」能解、项目级重排解不了的那一类）。
     * {@link #E_SLOT_NEIGHBOR}：两个单元所处时段<b>同一天且前后相邻</b>，
     * 是跨时段拆分可行的前提。</p>
     */
    public static final int E_HEAT_STAGGER = 8;
    public static final int E_SLOT_NEIGHBOR = 9;
    public static final int N_EDGES = 10;

    /** 节点特征维数：原 20 维 + 4 维微调信号（组次撞车/组次数/换组最优间隔/可拆性）。 */
    public static final int NODE_FEAT_DIM = 24;
    /** 图级（实例级）特征维数：冲突密度/规模/场地/天数/时间目标/并行度/填充率/块压力。 */
    /**
     * 图级特征维数：原 8 维 + 2 维**微调需求占比**
     * （这场赛会里有多少单元真需要组次错开 / 跨时段拆分）。
     * 路由器据此决定这两位新专家该不该上场，而不是「建了从不使用」。
     */
    public static final int GRAPH_FEAT_DIM = 10;
    /**
     * 任务（专家）总数。
     * ⚠️ 必须与 Python 侧 {@code super_scenarios.N_TASKS} 一致（双端契约）。
     * 这行曾经写死 9：合并裁判/教师（9→11）时漏改，直到本轮合并
     * GAN/Diffusion/forecast（11→17）才发现 —— 常量不同步本身不报错，
     * 只会在 Java 按错通道读 taskProbs 时静默取到错误维度。
     */
    public static final int N_TASKS = 19;
    /**
     * 有真实输入单元的「任务专家」个数（0..N_UNIT_TASKS-1 走**节点级**路由）；
     * 其余为「能力专家」（走**图级**路由，同一实例共享一份门控）。
     */
    public static final int N_UNIT_TASKS = 11;
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
            "兼项避让", "装箱容量", "工期压缩", "二次编排",
            // 第 10、11 类：裁判编排 / 教师规避（顺序即 ONNX 输出通道号，禁止改序）
            "裁判编排", "教师规避",
            // 第 12~17 类：**能力专家**（本轮把 7 个独立模型合并进来）。
            // 与前面 11 位不同，它们走「图级路由」——同一实例内所有节点共享一份门控，
            // 回答的是「这个赛会需要多少生成/精修/派遣/预测」而不是「这个单元怎么排」。
            "方案生成", "方案精修", "扩散去噪", "道次派遣", "工期预测", "方案判别",
            // 第 17、18 位：微调专属专家（2026-10-05）。精修链挪不动项目时间时
            // 的两条出路，各自输入依赖不同，故与前 12 位一样走图级路由。
            "组次顺序错开", "跨时段拆分"};


    /** 一个待编排单元。 */
    public record Unit(String key, String name, int task, boolean track, boolean team,
                       Integer format, String groupKey, String venue, String pool,
                       String grade, int duration, int interval, int heatCapacity,
                       List<Long> athletes, Integer bracketRound, String resecondOf,
                       String stage, Integer heatClashes, Integer bestStaggerGap,
                       Boolean canSplitSlot) {

        /** 组次级撞车强度（无则 0）。 */
        public int heatClashesOrZero() {
            return heatClashes == null ? 0 : heatClashes;
        }

        /** 换组次能拿到的最优间隔（分钟，无则 0）。 */
        public int bestStaggerGapOrZero() {
            return bestStaggerGap == null ? 0 : bestStaggerGap;
        }

        /** 是否可跨时段拆分（组次边界对得上且上午有零头、下午能承接）。 */
        public boolean canSplit() {
            return Boolean.TRUE.equals(canSplitSlot);
        }

        /** 组次数：与 heatCapacity 同一语义，此处只换一个说得通的名字。 */
        public int heatCount() {
            return heatCapacity;
        }

        public Unit {
            if (key == null) {
                key = "";
            }            if (name == null) {
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

        /**
         * 兼容旧调用点（无微调维度）。
         *
         * <p>2026-10-05 新增了三个微调字段。record 的规范构造器随之变长，
         * 会让<b>所有</b>既有调用点（球类编排 + 十几处单测）都要跟着补 null ——
         * 那种改法既噪音大又易漏（漏一处就是编译错误，还算好；更怕的是漏了语义）。</p>
         *
         * <p>这里给一个缺省「无微调信号」的构造器：老代码语义不变（该单元不参与
         * 组次错开/跨时段拆分），新代码用完整构造器传真实信号。</p>
         */
        public Unit(String key, String name, int task, boolean track, boolean team,
                    Integer format, String groupKey, String venue, String pool,
                    String grade, int duration, int interval, int heatCapacity,
                    List<Long> athletes, Integer bracketRound, String resecondOf,
                    String stage) {
            this(key, name, task, track, team, format, groupKey, venue, pool, grade,
                    duration, interval, heatCapacity, athletes, bracketRound, resecondOf, stage,
                    null, null, null);
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

        // ---- E_HEAT_STAGGER：组次级撞车（2026-10-05）----
        // 权重 = 撞车强度归一化。**不能用 E_ATHLETE 顶替**：兼项边只说
        // 「两人同报两项」，这条边说的是「那两个组次在时间上真的撞了」——
        // 后者才是组次错开能消解的直接信号。
        for (int i = 0; i < n; i++) {
            Unit u = units.get(i);
            if (u.heatClashesOrZero() <= 0 || u.heatCount() < 2) {
                continue;
            }
            float w = Math.min(1f, u.heatClashesOrZero() / 4f);
            for (int j = 0; j < n; j++) {
                if (i == j || units.get(j).heatClashesOrZero() <= 0
                        || units.get(j).heatCount() < 2) {
                    continue;
                }
                if (u.venue().equals(units.get(j).venue()) || sharesAthlete(u, units.get(j))) {
                    adj[E_HEAT_STAGGER][i][j] = w;
                    adj[E_HEAT_STAGGER][j][i] = w;
                }
            }
            tmask[E_HEAT_STAGGER] = 1f;
        }

        // ---- E_SLOT_NEIGHBOR：跨时段相邻（2026-10-05）----
        // 拆分只在「同一天相邻两个时段」之间发生，所以这条边直接编码可拆性。
        // 权重 = 两单元时长的接近程度（越接近越容易凑齐一段完整组次）。
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                Unit a = units.get(i);
                Unit b = units.get(j);
                if (!a.venue().equals(b.venue())) {
                    continue;
                }
                int mx = Math.max(a.duration(), b.duration());
                float w = mx <= 0 ? 0f : Math.min(1f, (float) Math.min(a.duration(), b.duration()) / mx);
                adj[E_SLOT_NEIGHBOR][i][j] = w;
                adj[E_SLOT_NEIGHBOR][j][i] = w;
                tmask[E_SLOT_NEIGHBOR] = 1f;
            }
        }

        // ---- 24 维节点特征 ----
        float[][] feat = new float[n][NODE_FEAT_DIM];
        double totalDur = units.stream().mapToInt(Unit::duration).sum();
        if (totalDur <= 0) {
            totalDur = 1;
        }
        int maxPeople = Math.max(1, units.stream()
                .mapToInt(u -> u.athletes().size()).max().orElse(1));
        int nVenues = Math.max(1, (int) units.stream().map(Unit::venue).distinct().count());
        // ⚠️ 图级第 2 维「场地数」口径必须与 super_encode.py 一致：**有窗口的场地数**
        //    （窗口 = 实际能排的时段场地），不是「单元里出现过的场地数」，也不是
        //    「场景声明的全部场地」—— 后两者在「声明了场地但没排任何单元/时段」时
        //    会把 Java 与 Python 算出不同的值，路由拿到错的结构信号还不报错。
        //    windows 为空时退回按单元统计，保证不会是 0。
        int nActiveVenues = 0;
        if (windows != null) {
            nActiveVenues = (int) windows.stream().map(Window::venue).distinct().count();
        }
        if (nActiveVenues <= 0) {
            nActiveVenues = nVenues;
        }
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
                    fmtOh0, fmtOh1, fmtOh2, fmtOh3,               // 15-18 赛制 onehot
                    // ---- 19-22：微调信号（2026-10-05 新增）----
                    // 只回答两问：「要不要错开组次」与「能不能跨时段拆」。
                    // 缺数据即 0（无组次语义 = 不参与微调）。
                    Math.min(1f, u.heatClashesOrZero() / 4f),                    // 19 组次撞车强度
                    u.heatCount() > 0 ? Math.min(1f, u.heatCount() / 12f) : 0f,   // 20 组次数
                    Math.min(1f, u.bestStaggerGapOrZero() / 60f),                 // 21 换组最优间隔
                    u.canSplit() ? 1f : 0f,                                       // 22 跨时段拆分可行
            };
        }
        // ---- 图级（实例级）特征：路由器据此判断「这场赛会该派哪位专家」 ----
        // ⚠️ 十维必须与 sports_ai/data/super_encode.py 的 graph_feat 逐位对齐，
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
            // ⚠️ 每时段并行场地数 = 该时段的**不同场地数**，不是窗口条数。
            //    早期按窗口条数 merge(+1) 计数，与 super_encode.py 的
            //    「len(set(venues)) 取最大」不同口径；同一场地在一个时段开两条
            //    窗口（如上午两节）时 Java 会比 Python 大。
            Map<String, Set<String>> perSlot = new LinkedHashMap<>();
            for (Window w : windows) {
                perSlot.computeIfAbsent(w.day() + "#" + w.windowIdx(), k -> new LinkedHashSet<>())
                        .add(w.venue());
                totalCapacity += w.capacity();
            }
            for (Set<String> vs : perSlot.values()) {
                maxParallel = Math.max(maxParallel, vs.size());
            }
        }
        int demand = 0;
        for (Unit u : units) {
            demand += u.duration() + u.interval();
        }
        int blockCount = blockSize.size();
        // 微调需求占比（与 super_encode.py 同口径）：末两维告诉路由器
        // 「这场赛会有多少单元真需要组次错开 / 跨时段拆分」——
        // 没有它们，两位新专家就会因拿不到实例级信号而形同虚设。
        int nStagger = 0;
        int nSplit = 0;
        for (Unit u : units) {
            if (u.heatClashesOrZero() > 0 && u.heatCount() >= 2) {
                nStagger++;
            }
            if (u.canSplit()) {
                nSplit++;
            }
        }
        float staggerNeed = Math.min(1f, nStagger / Math.max(1f, n / 8f));
        float splitNeed = Math.min(1f, nSplit / Math.max(1f, n / 8f));
        float[] graphFeat = new float[]{
                (float) Math.min(1d, confDensity * 8d),                        // 0 冲突密度
                Math.min(1f, n / 128f),                                        // 1 单元规模
                Math.min(1f, nActiveVenues / 12f),                              // 2 场地数（有窗口的场地）
                Math.min(1f, maxDay / 7f),                                     // 3 天数
                timeGoal,                                                      // 4 时间目标（0/0.5/1）
                Math.min(1f, maxParallel / 4f),                                // 5 每时段并行场地数
                // ⚠️ 必须强转 float 再除：demand / totalCapacity 两边都是 int，
                //    Java 会先做**整数除法截断**（5000/12000 = 0），于是填充率恒为 0，
                //    图级路由少看到一个最关键的结构信号（而且不报错）。
                Math.min(1f, demand / (float) Math.max(1, totalCapacity)),       // 6 填充率
                Math.min(1f, blockCount / (float) Math.max(1, maxDay) / 4f),  // 7 块压力
                staggerNeed,                                                 // 8 组次错开需求
                splitNeed,                                                   // 9 跨时段拆分需求
        };

        return new Encoded(feat, adj, tmask, mask, graphFeat, n, daysLimit, false, null);
    }

    // ------------------------------------------------------------------
    /** 两个单元是否共享运动员（小集合驱动，避免全量遍历） */
    private static boolean sharesAthlete(Unit a, Unit b) {
        if (a.athletes() == null || b.athletes() == null
                || a.athletes().isEmpty() || b.athletes().isEmpty()) {
            return false;
        }
        return new HashSet<>(a.athletes()).stream().anyMatch(b.athletes()::contains);
    }

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
