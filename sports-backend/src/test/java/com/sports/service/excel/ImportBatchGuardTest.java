package com.sports.service.excel;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ImportBatchGuard} 单测：去重、同键冲突、项目命名空间合并、名单 vs 报名不一致。
 */
class ImportBatchGuardTest {

    private static Map<String, String> row(String... kv) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void duplicateSameKeySameFieldsIsDuplicate() {
        ImportBatchGuard g = new ImportBatchGuard();
        Map<String, String> a = row("studentId", "202610101", "name", "陈磊", "className", "高一1班");

        assertTrue(g.check("athlete", a, "f1[名单] 第2行").isEmpty(), "首次出现不应报问题");
        List<ImportBatchGuard.Issue> issues = g.check("athlete", a, "f2[名单] 第3行");

        assertEquals(1, issues.size());
        assertEquals(ImportBatchGuard.Kind.DUPLICATE, issues.get(0).kind());
    }

    @Test
    void sameKeyDifferentValuesIsConflict() {
        ImportBatchGuard g = new ImportBatchGuard();
        g.check("athlete", row("studentId", "1", "name", "张三", "gender", "男"), "f[名单] 第2行");

        List<ImportBatchGuard.Issue> issues =
                g.check("athlete", row("studentId", "1", "name", "张山", "gender", "男"), "f[名单] 第5行");

        assertEquals(1, issues.size());
        assertEquals(ImportBatchGuard.Kind.CONFLICT, issues.get(0).kind());
        assertTrue(issues.get(0).detail().contains("姓名"), "冲突应指出差异字段: " + issues.get(0).detail());
    }

    @Test
    void eventAndEventSimpleShareProjectNamespace() {
        ImportBatchGuard g = new ImportBatchGuard();
        // 项目表（表格2）用 name/code；运动项目表用 eventCode/eventName —— 同一项目编码应被去重
        g.check("event", row("code", "50M", "name", "50米"), "f[项目表] 第2行");

        List<ImportBatchGuard.Issue> issues =
                g.check("eventsimple", row("eventCode", "50M", "eventName", "50米"), "f[运动项目表] 第2行");

        assertEquals(1, issues.size(), "两张项目表里的同一编码应判为重复");
        assertEquals(ImportBatchGuard.Kind.DUPLICATE, issues.get(0).kind());
    }

    @Test
    void signupDuplicateByAthleteAndEvent() {
        ImportBatchGuard g = new ImportBatchGuard();
        Map<String, String> s = row("studentId", "1", "name", "张三", "eventCode", "100M");
        assertTrue(g.check("signup", s, "f[报名] 第2行").isEmpty());
        List<ImportBatchGuard.Issue> after = g.check("signup", s, "f[报名] 第9行");
        assertEquals(ImportBatchGuard.Kind.DUPLICATE, after.get(0).kind());
    }

    @Test
    void signupMismatchWithRosterIsReportedButNotSkipped() {
        ImportBatchGuard g = new ImportBatchGuard();
        // 阶段 A：建名单索引
        g.indexAthlete("roster", row("studentId", "1", "name", "张三", "gender", "男", "className", "高一1班"));

        // 报名里性别写成「女」→ 名单不一致（提示，但不阻止导入）
        List<ImportBatchGuard.Issue> issues = g.check("signup",
                row("studentId", "1", "name", "张三", "gender", "女", "className", "高一1班", "eventCode", "100M"),
                "f[报名] 第3行");

        assertEquals(1, issues.size());
        assertEquals(ImportBatchGuard.Kind.ROSTER_MISMATCH, issues.get(0).kind());
        assertTrue(issues.get(0).detail().contains("性别"), issues.get(0).detail());
    }
}
