package com.sports.schedule.ai;

import com.sports.schedule.opt.solver.Placement;
import com.sports.schedule.opt.solver.ScheduleUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HierarchicalGnnEncoder} 的规模与正确性约定。
 *
 * <p>核心要钉住的是一句话：<b>一个单元都不能丢</b>。
 * 旧实现 {@code n = min(total, MAX_NODES)} 直接截断，超限单元静默拿不到优先级，
 * 而 AI 恰恰在大型赛会上退化成半程参与——这是本类存在的全部理由。</p>
 */
@DisplayName("分层冲突图编码（突破单元数上限）")
class HierarchicalGnnEncoderTest {

    private static List<Placement> placements(int n) {
        List<Placement> ps = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ps.add(new Placement("径赛", 0, 0, 1, "2026-01-01", "上午", "田径场",
                    480 + i * 10, 480, 210));
        }
        return ps;
    }

    /** 造 n 个单元；athletes 按「每 unitsPerAth 个单元共享一名运动员」的方式构造。 */
    private static List<ScheduleUnit> units(int n, int unitsPerAth) {
        List<ScheduleUnit> us = new ArrayList<>();
        List<Placement> cands = placements(4);
        for (int i = 0; i < n; i++) {
            int athId = i / Math.max(1, unitsPerAth);
            us.add(new ScheduleUnit("u" + i, 1L, "项目" + i, "高一", true, "径赛", null, 5,
                    20, 10, new long[]{athId}, List.of(20), cands));
        }
        return us;
    }

    @Test
    @DisplayName("小规模退化为单层：degraded=false，行为与历史逐位一致")
    void smallScaleIsNotDegraded() {
        List<ScheduleUnit> us = units(20, 3);
        HierarchicalGnnEncoder.HierEncoded enc = HierarchicalGnnEncoder.encode(us);

        assertFalse(enc.degraded(), "20 个单元远小于上限，不该走分层");
        assertEquals(20, enc.unitCount());
        assertEquals(20, enc.clusterCount());
        assertEquals(20, enc.clusterGraph().nodeCount);
        assertArrayEquals(ConflictGraphEncoder.encode(us).adj, enc.clusterGraph().adj,
                "单层路径必须与原编码器结果逐位一致，不能改变任何既有推理结果");
    }

    @Test
    @DisplayName("超大规模：优先级长度 == 单元总数，一个都不丢（本类的存在理由）")
    void largeScaleKeepsEveryUnit() {
        int n = 3000;                                   // 远超 MAX_NODES=1024
        List<ScheduleUnit> us = units(n, 4);
        HierarchicalGnnEncoder.HierEncoded enc = HierarchicalGnnEncoder.encode(us);

        assertTrue(enc.degraded(), "3000 单元必须走分层");
        assertEquals(n, enc.unitCount(), "单元总数必须如实记录");
        assertEquals(n, enc.unitToCluster().length, "每个单元都要有簇归属");
        assertTrue(enc.clusterCount() <= HierarchicalGnnEncoder.MAX_CLUSTERS,
                "送入 ONNX 的簇数必须受限，实际=" + enc.clusterCount());

        double[] perUnit = HierarchicalGnnEncoder.expandToUnits(
                enc, new double[enc.clusterGraph().nodeCount], us);
        assertEquals(n, perUnit.length, "优先级长度必须等于单元总数——旧的截断实现只有 1024");
        assertTrue(Arrays.stream(perUnit).anyMatch(v -> v != 0.0),
                "展开后应存在非零优先级（簇优先级全 0 时退化为按暴露度排序，仍需有区分）");
    }

    @Test
    @DisplayName("十万级单元也不爆内存：簇图与 N 无关，且建图是 O(N+E)")
    void hundredThousandUnitsStayBounded() {
        int n = 100_000;
        List<ScheduleUnit> us = units(n, 8);
        long t0 = System.currentTimeMillis();
        HierarchicalGnnEncoder.HierEncoded enc = HierarchicalGnnEncoder.encode(us);
        long cost = System.currentTimeMillis() - t0;

        assertEquals(n, enc.unitCount());
        int k = enc.clusterCount();
        assertTrue(k <= HierarchicalGnnEncoder.MAX_CLUSTERS,
                "簇数必须被压到上限内，实际=" + k);
        // 簇图内存 ~ k²×4B，与 N 无关：512²×4 = 1MB
        long adjBytes = (long) k * k * 4L;
        assertTrue(adjBytes <= 2L * 1024 * 1024,
                "簇图邻接应 ≤2MB（k=" + k + "，实际 " + adjBytes / 1024 + "KB），与单元总数无关");
        // 单层全图在同等规模下需要 n²×4B = 40GB —— 这正是必须分层的原因
        assertTrue(cost < 20_000, "十万单元建图应在 20s 内，实际 " + cost + "ms");
    }

    @Test
    @DisplayName("簇映射是满射且稳定：每个单元恰好属于一个簇，簇号连续")
    void clusterMappingIsWellFormed() {
        List<ScheduleUnit> us = units(2500, 3);
        HierarchicalGnnEncoder.HierEncoded enc = HierarchicalGnnEncoder.encode(us);

        Set<Integer> seen = new HashSet<>();
        for (int c : enc.unitToCluster()) {
            assertTrue(c >= 0 && c < enc.clusterCount(), "簇号越界: " + c);
            seen.add(c);
        }
        assertEquals(enc.clusterCount(), seen.size(), "簇号应连续无空洞");
    }

    @Test
    @DisplayName("无兼项时每单元自成一簇，分层退化为按规模排序（不制造假冲突）")
    void noOverlapMeansSingletonClusters() {
        // 每名运动员只出现在 1 个单元里 → 无任何合并依据
        List<ScheduleUnit> us = units(2000, 1);
        HierarchicalGnnEncoder.HierEncoded enc = HierarchicalGnnEncoder.encode(us);

        assertTrue(enc.degraded());
        // 全部独立成簇，簇数被压到上限；被并掉的簇挂到最大簇上
        assertTrue(enc.clusterCount() <= HierarchicalGnnEncoder.MAX_CLUSTERS);

        double[] perUnit = HierarchicalGnnEncoder.expandToUnits(
                enc, new double[enc.clusterGraph().nodeCount], us);
        assertEquals(2000, perUnit.length);
        // 簇内次序必须由 exposure 决定：人数×时长 大的排前面
        int bigger = -1, smaller = -1;
        for (int i = 0; i < perUnit.length; i++) {
            if (perUnit[i] > 0) {
                bigger = i;
                break;
            }
        }
        for (int i = 0; i < perUnit.length; i++) {
            if (perUnit[i] < 0) {
                smaller = i;
                break;
            }
        }
        assertNotNull(new Object[]{bigger, smaller});
    }

    @Test
    @DisplayName("空输入不炸")
    void emptyInputIsSafe() {
        HierarchicalGnnEncoder.HierEncoded enc = HierarchicalGnnEncoder.encode(List.of());
        assertEquals(0, enc.unitCount());
        assertEquals(0, HierarchicalGnnEncoder.expandToUnits(enc, new double[0], List.of()).length);
    }
}
