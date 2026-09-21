package com.sports.service.excel;

import com.sports.entity.ClassInfo;
import com.sports.entity.Event;
import com.sports.repository.*;
import com.sports.service.ExcelService;
import com.sports.service.GradeService;
import com.sports.support.ExcelTestDataFactory;
import com.sports.support.ExcelTestDataFactory.SheetSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 多表导入（管理员）：<b>一个工作簿多个 Sheet</b> / <b>多个文件</b> 一次导入。
 *
 * <p>用例全部用 {@link ExcelTestDataFactory} 现场生成工作簿（不硬编码样例文件），
 * 并走真实的 {@link ExcelService} 行处理器（仅 Repository 为 mock）——这样「表头自动映射」
 * 与「逐行落库」都是真跑的，只有数据库被替换。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("多表导入（多 Sheet / 多文件）")
class MultiTableImportServiceTest {

    @Mock private AthleteRepository athleteRepository;
    @Mock private RegistrationRepository registrationRepository;
    @Mock private ResultRepository resultRepository;
    @Mock private EventRepository eventRepository;
    @Mock private ClassInfoRepository classInfoRepository;
    @Mock private ArrangementRepository arrangementRepository;
    @Mock private EventScheduleRepository scheduleRepository;
    @Mock private EventRefereeRepository eventRefereeRepository;
    @Mock private RefereeRepository refereeRepository;
    @Mock private VenueRepository venueRepository;
    @Mock private GradeService gradeService;

    /** 用 Spy 包住真实 ExcelService：既能执行真实行处理，又能验证「按依赖顺序调用」。 */
    @Spy @InjectMocks private ExcelService excelService;

    private MultiTableImportService service;

    @BeforeEach
    void setUp() {
        service = new MultiTableImportService(excelService);
        // 基础数据桩：班级不存在（走自动创建）、运动员不存在（走新建）、年级列表为空（无重复）
        when(classInfoRepository.findByGradeAndName(any(), any())).thenReturn(Optional.empty());
        when(classInfoRepository.findByName(any())).thenReturn(Optional.empty());
        when(classInfoRepository.existsByCode(any())).thenReturn(false);
        when(classInfoRepository.existsByName(any())).thenReturn(false);
        when(classInfoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(athleteRepository.findByStudentId(any())).thenReturn(Optional.empty());
        when(registrationRepository.existsByAthleteIdAndEventId(any(), any())).thenReturn(false);
        when(gradeService.getGrades()).thenReturn(new ArrayList<>());
    }

    private static MockMultipartFile file(byte[] bytes, String name) {
        return new MockMultipartFile("files", name,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
    }

    private static SheetSpec gradeSheet(String sheetName, String[][] rows) {
        return SheetSpec.of(sheetName, List.of("年级", "序号"), rows);
    }

    private static SheetSpec classSheet(String sheetName, String[][] rows) {
        return SheetSpec.of(sheetName, List.of("班级名称", "年级", "班主任"), rows);
    }

    private static SheetSpec rosterSheet(String sheetName, String[][] rows) {
        return SheetSpec.of(sheetName, List.of("年级", "班级", "姓名", "学号", "性别"), rows);
    }

    private static SheetSpec signupSheet(String sheetName, String[][] rows) {
        return SheetSpec.of(sheetName, List.of("年级", "班级", "姓名", "学号", "性别", "项目", "组号"), rows);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> sheetsOf(Map<String, Object> result, int fileIdx) {
        List<Map<String, Object>> files = (List<Map<String, Object>>) result.get("files");
        return (List<Map<String, Object>>) files.get(fileIdx).get("sheets");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sheetByName(Map<String, Object> result, String name) {
        for (Map<String, Object> s : sheetsOf(result, 0)) {
            if (name.equals(s.get("sheetName"))) {
                return s;
            }
        }
        throw new AssertionError("报告里没有 Sheet: " + name);
    }

    private static Map<String, Object> summaryOf(Map<String, Object> result) {
        return (Map<String, Object>) result.get("summary");
    }

    // ==================== 一个工作簿多 Sheet ====================

    @Test
    @DisplayName("一个工作簿多个 Sheet：按表头/表名各自识别并全部导入")
    void multiSheetWorkbook() {
        byte[] book = ExcelTestDataFactory.xlsxMulti(List.of(
                gradeSheet("年级表", new String[][]{{"高一年级", "1"}, {"高二年级", "2"}}),
                classSheet("班级表", new String[][]{{"高一1班", "高一年级", "王老师"}}),
                rosterSheet("全名单", new String[][]{{"高一年级", "高一1班", "张三", "2024001", "男"}})));

        Map<String, Object> result = service.importAll(List.of(file(book, "多表工作簿.xlsx")), Map.of());

        assertEquals("grade", sheetByName(result, "年级表").get("type"));
        assertEquals("class", sheetByName(result, "班级表").get("type"));
        assertEquals("roster", sheetByName(result, "全名单").get("type"));
        assertEquals(0, summaryOf(result).get("failed"));
        assertEquals(4, summaryOf(result).get("imported"));
        assertEquals(0, summaryOf(result).get("skippedSheets"));
    }

    @Test
    @DisplayName("物理顺序被打乱也按依赖顺序执行：「报名表」排在最前仍先导名单与项目")
    void dependencyOrderOverridesSheetOrder() {
        Event relay = Event.builder().id(9L).code("4X100M").name("4×100米接力")
                .team(true).teamMembers(4).isEnabled(true).track(true).build();
        when(eventRepository.findByCode("4X100M")).thenReturn(Optional.of(relay));
        ClassInfo cls = ClassInfo.builder().id(3L).name("高一1班").grade("高一年级").build();
        when(athleteRepository.findByName("张三")).thenReturn(List.of(
                com.sports.entity.Athlete.builder().id(7L).name("张三").classInfo(cls).build()));

        byte[] book = ExcelTestDataFactory.xlsxMulti(List.of(
                // 故意把报名表放在第 0 个 Sheet：若按物理顺序处理，运动员/项目都还不存在，整表必失败
                signupSheet("报名表", new String[][]{
                        {"高一年级", "高一1班", "张三", "2024001", "男", "4X100M", "A"}}),
                rosterSheet("全名单", new String[][]{{"高一年级", "高一1班", "张三", "2024001", "男"}}),
                gradeSheet("年级表", new String[][]{{"高一年级", "1"}})));

        Map<String, Object> result = service.importAll(List.of(file(book, "乱序工作簿.xlsx")), Map.of());

        assertEquals(0, summaryOf(result).get("failed"), "按依赖顺序处理后不应有失败行");
        InOrder order = inOrder(excelService);
        order.verify(excelService).importRows(eq("grade"), anyList());
        order.verify(excelService).importRows(eq("roster"), anyList());
        order.verify(excelService).importRows(eq("signup"), anyList());
    }

    // ==================== 认不出就跳过，绝不硬猜 ====================

    @Test
    @DisplayName("认不出类型的 Sheet 跳过并给出原因，不影响其它 Sheet")
    void unknownSheetIsSkippedWithReason() {
        byte[] book = ExcelTestDataFactory.xlsxMulti(List.of(
                SheetSpec.of("会议纪要", List.of("日期", "议题", "结论"), new String[][]{{"9月1日", "开幕式", "通过"}}),
                rosterSheet("全名单", new String[][]{{"高一年级", "高一1班", "张三", "2024001", "男"}})));

        Map<String, Object> result = service.importAll(List.of(file(book, "混合.xlsx")), Map.of());

        Map<String, Object> skipped = sheetByName(result, "会议纪要");
        assertEquals(Boolean.TRUE, skipped.get("skipped"));
        assertNull(skipped.get("type"));
        assertNotNull(skipped.get("reason"));
        assertEquals(1, summaryOf(result).get("skippedSheets"));
        assertEquals(1, summaryOf(result).get("imported"));
        assertEquals(0, summaryOf(result).get("failed"));
    }

    @Test
    @DisplayName("人工指定类型 + 列映射：表头不可识别的 Sheet 也能导入")
    void overrideTypeAndColumnMap() {
        byte[] book = ExcelTestDataFactory.xlsxMulti(List.of(
                SheetSpec.of("Sheet1", List.of("甲", "乙", "丙"),
                        new String[][]{{"高一年级", "张三", "2024001"}})));

        Map<String, Object> plan = new LinkedHashMap<>();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("file", "手工指定.xlsx");
        entry.put("sheet", "Sheet1");
        entry.put("type", "roster");
        entry.put("columnMap", Map.of("0", "grade", "1", "name", "2", "studentId"));
        plan.put("sheets", List.of(entry));

        Map<String, Object> result = service.importAll(List.of(file(book, "手工指定.xlsx")), plan);

        Map<String, Object> sheet = sheetByName(result, "Sheet1");
        assertEquals("roster", sheet.get("type"));
        assertEquals("override", sheet.get("resolvedBy"));
        assertEquals(1, summaryOf(result).get("imported"));
        verify(athleteRepository).save(any());
    }

    // ==================== 多文件 ====================

    @Test
    @DisplayName("多文件一次导入：两个文件各自的 Sheet 都被处理")
    void multipleFilesInOneCall() {
        byte[] a = ExcelTestDataFactory.xlsxMulti(List.of(
                rosterSheet("全名单", new String[][]{{"高一年级", "高一1班", "张三", "2024001", "男"}})));
        byte[] b = ExcelTestDataFactory.xlsxMulti(List.of(
                rosterSheet("全名单", new String[][]{{"高二年级", "高二3班", "李四", "2024002", "女"}})));

        Map<String, Object> result = service.importAll(List.of(file(a, "高一.xlsx"), file(b, "高二.xlsx")), Map.of());

        assertEquals(2, summaryOf(result).get("files"));
        assertEquals(2, summaryOf(result).get("sheets"));
        assertEquals(2, summaryOf(result).get("imported"));
        assertEquals(0, summaryOf(result).get("failed"));
    }

    // ==================== 重跑：已存在归「跳过」而非「失败」 ====================

    @Test
    @DisplayName("重跑同一份工作簿：已存在的班级归入「跳过」，不报失败")
    void duplicateRowsCountAsSkipped() {
        when(classInfoRepository.existsByName("高一1班")).thenReturn(true);

        byte[] book = ExcelTestDataFactory.xlsxMulti(List.of(
                classSheet("班级表", new String[][]{{"高一1班", "高一年级", "王老师"}})));

        Map<String, Object> result = service.importAll(List.of(file(book, "重跑.xlsx")), Map.of());

        Map<String, Object> sheet = sheetByName(result, "班级表");
        assertEquals(1, sheet.get("rowSkipped"));
        assertEquals(0, sheet.get("failed"));
        assertEquals(0, summaryOf(result).get("failed"));
        assertEquals(1, summaryOf(result).get("rowSkipped"));
    }

    // ==================== 探测（不落库） ====================

    @Test
    @DisplayName("探测：返回真实 Sheet 名、判定类型、自动列映射与可否导入")
    void previewReportsSheetInventory() {
        byte[] book = ExcelTestDataFactory.xlsxMulti(List.of(
                gradeSheet("年级表", new String[][]{{"高一年级", "1"}}),
                SheetSpec.of("会议纪要", List.of("日期", "议题"), new String[][]{{"9月1日", "开幕式"}})));

        Map<String, Object> preview = service.preview(List.of(file(book, "探测.xlsx")), Map.of());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sheets = (List<Map<String, Object>>)
                ((List<Map<String, Object>>) preview.get("files")).get(0).get("sheets");
        assertEquals(2, sheets.size());
        assertEquals("年级表", sheets.get(0).get("sheetName"));
        assertEquals("grade", sheets.get(0).get("type"));
        assertEquals(Boolean.TRUE, sheets.get(0).get("importable"));
        assertEquals(Map.of("0", "name", "1", "sortOrder"), sheets.get(0).get("columnMap"));

        assertEquals("会议纪要", sheets.get(1).get("sheetName"));
        assertEquals(Boolean.FALSE, sheets.get(1).get("importable"));
        assertNotNull(sheets.get(1).get("reason"));
        verify(athleteRepository, never()).save(any());
    }

    @Test
    @DisplayName("「高一年级」这类按年级拆分的名单 Sheet 由表头判定为名单表，不会误当年级主数据")
    void perGradeRosterSheetIsNotMistakenForGradeMaster() {
        byte[] book = ExcelTestDataFactory.xlsxMulti(List.of(
                rosterSheet("高一年级", new String[][]{{"高一年级", "高一1班", "张三", "2024001", "男"}})));

        Map<String, Object> result = service.importAll(List.of(file(book, "分年级名单.xlsx")), Map.of());

        assertEquals("roster", sheetByName(result, "高一年级").get("type"));
        assertEquals("header", sheetByName(result, "高一年级").get("resolvedBy"));
        assertEquals(1, summaryOf(result).get("imported"));
    }

    @Test
    @DisplayName("空行不产生失败行；空 Sheet 不报错（跳过并说明原因）")
    void blankRowsIgnored() {
        byte[] book = ExcelTestDataFactory.xlsxMulti(List.of(
                new SheetSpec("全名单", List.of("年级", "班级", "姓名", "学号", "性别"),
                        List.of(List.of("高一年级", "高一1班", "张三", "2024001", "男"),
                                List.of(" ", "", "", "", ""),
                                List.of("高一年级", "高一1班", "李四", "2024002", "女"))),
                new SheetSpec("班级表", List.of("班级名称", "年级", "班主任"), List.of())));

        Map<String, Object> result = service.importAll(List.of(file(book, "含空行.xlsx")), Map.of());

        // 关键断言：空行绝不能被算成「失败」——重复导入/手抖空行是最常见的情形
        assertEquals(2, sheetByName(result, "全名单").get("success"));
        assertEquals(0, sheetByName(result, "全名单").get("failed"));
        assertEquals(0, summaryOf(result).get("failed"));

        // 空 Sheet（只有表头、没有数据）：跳过且给出原因，不影响其它表
        Map<String, Object> emptySheet = sheetByName(result, "班级表");
        assertEquals(0, emptySheet.get("totalRows"));
        assertEquals(Boolean.FALSE, emptySheet.get("skipped"), "有表头可识别列，应可导入（只是 0 行）");
    }

    @Test
    @DisplayName("空白行判定：空 Map / 全空串 / 全空白符 都算空行")
    void blankRowDetection() {
        assertTrue(ExcelSheetReader.isBlankRow(Map.of()));
        assertTrue(ExcelSheetReader.isBlankRow(Map.of(0, "", 1, "   ")));
        assertFalse(ExcelSheetReader.isBlankRow(Map.of(0, "", 1, "张三")));
    }

    @Test
    @DisplayName("未选择文件：明确报错，不静默成功")
    void noFilesThrows() {
        assertThrows(RuntimeException.class, () -> service.importAll(List.of(), Map.of()));
    }

    @Test
    @DisplayName("ClassInfo 自动创建：名单表里出现的班级会被建出来")
    void rosterAutoCreatesClass() {
        byte[] book = ExcelTestDataFactory.xlsxMulti(List.of(
                rosterSheet("全名单", new String[][]{{"高一年级", "高一9班", "赵六", "2024009", "男"}})));

        service.importAll(List.of(file(book, "自动建班.xlsx")), Map.of());

        verify(classInfoRepository).save(any(ClassInfo.class));
        verify(athleteRepository).save(any());
    }
}
