package com.sports;

import com.sports.common.util.Grades;
import com.sports.entity.athlete.Athlete;
import com.sports.repository.athlete.AthleteRepository;
import com.sports.repository.clazz.ClassInfoRepository;
import com.sports.service.excel.MultiTableImportService;
import com.sports.support.ExcelTestDataFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模糊年级 · 真库端到端（走用户实际使用的「多表导入」链路）。
 *
 * <p>单元测试只能证明 mock 下的分支行为；本用例在**真实 SQLite + 真实 EasyExcel 解析 +
 * 真实 JPA** 上验证：「高一 / 高一年级 / 10年级 / Grade 10」四种写法导入后
 * ①球员 grade 全部收敛为「高一」；②四个不同写法的班级名归到同一个班（不重复建班）。</p>
 *
 * <p>测试方法带 {@code @Transactional}，结束后回滚，不污染开发库。</p>
 */
@SpringBootTest
@Transactional
@DisplayName("模糊年级 · 真库端到端")
class GradeFuzzyImportIntegrationTest {

    @Autowired
    private MultiTableImportService multiTableImportService;
    @Autowired
    private AthleteRepository athleteRepository;
    @Autowired
    private ClassInfoRepository classInfoRepository;

    @Test
    @DisplayName("四种年级/班级写法 → 同一个「高一」+ 同一个「高一1班」")
    void fuzzySpellingsCollapseToOneGradeAndOneClass() {
        byte[] book = ExcelTestDataFactory.xlsxMulti(List.of(
                ExcelTestDataFactory.SheetSpec.of("名单",
                        List.of("姓名", "性别", "年级", "班级", "学号", "号码布编号"),
                        new String[][]{
                                {"集成甲", "男", "高一", "高一1班", "IT2026001", "IT90001"},
                                {"集成乙", "女", "高一年级", "高一年级1班", "IT2026002", "IT90002"},
                                {"集成丙", "男", "10年级", "10年级1班", "IT2026003", "IT90003"},
                                {"集成丁", "女", "Grade 10", "Grade 10 1班", "IT2026004", "IT90004"}})));

        MockMultipartFile file = new MockMultipartFile("files", "模糊年级名单.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", book);

        Map<String, Object> result = multiTableImportService.importAll(List.of(file), Map.of());

        Object summary = result.get("summary");
        assertTrue(summary instanceof Map, "应返回 summary: " + result);
        int imported = ((Number) ((Map<?, ?>) summary).get("imported")).intValue();
        assertEquals(4, imported, "四种写法都应导入成功: " + result);

        for (String sid : List.of("IT2026001", "IT2026002", "IT2026003", "IT2026004")) {
            Athlete a = athleteRepository.findByStudentId(sid).orElse(null);
            assertNotNull(a, "应成功落库: " + sid);
            assertEquals("高一", a.getGrade(), "年级应收敛为规范名「高一」: " + sid);
            assertNotNull(a.getClassInfo(), "应关联到班级: " + sid);
            assertEquals("高一1班", a.getClassInfo().getName(), "班级名应收敛为「高一1班」: " + sid);
        }

        long sameClass = classInfoRepository.findAll().stream()
                .filter(c -> "G10#1".equals(Grades.classKey(c.getName())))
                .count();
        assertEquals(1, sameClass, "四种班级写法应收敛为同一个班（不重复建班）");
    }
}
