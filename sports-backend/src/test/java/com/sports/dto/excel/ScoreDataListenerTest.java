package com.sports.dto.excel;

import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.exception.ExcelDataConvertException;
import com.alibaba.excel.read.metadata.holder.ReadRowHolder;
import com.alibaba.excel.read.metadata.holder.ReadSheetHolder;
import com.sports.entity.athlete.Athlete;
import com.sports.entity.clazz.ClassInfo;
import com.sports.entity.event.Event;
import com.sports.entity.result.Result;
import com.sports.repository.arrange.ArrangementRepository;
import com.sports.repository.athlete.AthleteRepository;
import com.sports.repository.event.EventRepository;
import com.sports.repository.result.ResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 成绩导入监听器测试（表头驱动版）。
 * 覆盖：R-2 非完赛标记（DNF/DNS/DSQ）、R-3 畸形/越界时间；
 *      表头别名定位（乱序列序 / 多余年级班级列）、文本组别/道次容错回退编排、
 *      项目名称兜底匹配、学号匹配、单元格转换异常只跳行不炸导入。
 */
@ExtendWith(MockitoExtension.class)
class ScoreDataListenerTest {

    @Mock private ResultRepository resultRepository;
    @Mock private EventRepository eventRepository;
    @Mock private AthleteRepository athleteRepository;
    @Mock private ArrangementRepository arrangementRepository;

    private ScoreDataListener listener;

    @BeforeEach
    void setup() {
        listener = new ScoreDataListener(resultRepository, eventRepository, athleteRepository, arrangementRepository);
    }

    // ==================== 测试工具 ====================

    private AnalysisContext ctx(int rowIndex) {
        AnalysisContext ctx = mock(AnalysisContext.class);
        ReadRowHolder row = mock(ReadRowHolder.class);
        when(ctx.readRowHolder()).thenReturn(row);
        when(row.getRowIndex()).thenReturn(rowIndex);
        when(ctx.readSheetHolder()).thenReturn(null);
        return ctx;
    }

    /** 与旧 ScoreExcelModel 固定 index 一致的标准表头（无表头测试不调用它） */
    private LinkedHashMap<Integer, String> header() {
        String[] head = {"项目编码", "运动员号码", "运动员姓名", "成绩", "组别", "道次", "风速", "备注"};
        LinkedHashMap<Integer, String> m = new LinkedHashMap<>();
        for (int i = 0; i < head.length; i++) m.put(i, head[i]);
        return m;
    }

    /** 无表头文件：按固定列序给的数据行（0编码/1号码/3成绩） */
    private LinkedHashMap<Integer, String> row(String eventCode, String number, String rawTime) {
        LinkedHashMap<Integer, String> m = new LinkedHashMap<>();
        m.put(0, eventCode);
        m.put(1, number);
        m.put(3, rawTime);
        return m;
    }

    /** 批处理：invoke 只累积到 batch，需在 doAfterAllAnalysed 里 flush 才落库。
     *  saveAll 拿到的是 batch 的活引用、flush 随后会清空它，故在 answer 内即时拷贝内容，
     *  避免抓到被清空的引用。返回本次落库的所有 Result。 */
    @SuppressWarnings("unchecked")
    private List<Result> flushAndGetSaved(ScoreDataListener l) {
        List<Result> captured = new ArrayList<>();
        when(resultRepository.saveAll(anyList())).thenAnswer(inv -> {
            List<Result> arg = inv.getArgument(0);
            captured.addAll(arg);
            return new ArrayList<>(arg);
        });
        // doAfterAllAnalysed 不读取 context，传 null 即可（避免 ctx() 桩被 Mockito 严格模式判为多余）
        l.doAfterAllAnalysed(null);
        return captured;
    }

    /** 便捷重载：针对测试默认 listener 字段 */
    private List<Result> flushAndGetSaved() {
        return flushAndGetSaved(listener);
    }

    private void stubBasics() {
        Event e = Event.builder().id(1L).code("M100").name("100米").build();
        when(eventRepository.findByCode("M100")).thenReturn(Optional.of(e));
        Athlete a = Athlete.builder().id(1L).name("张三").number("1001").grade("高一").gender("男")
                .classInfo(ClassInfo.builder().id(1L).name("高一1班").grade("高一").build()).build();
        when(athleteRepository.findByNumber("1001")).thenReturn(Optional.of(a));
        when(resultRepository.findByEventIdAndAthleteId(1L, 1L)).thenReturn(Optional.empty());
        // arrangement 查询仅成功落库路径会命中；R-3 畸形/越界时间在解析阶段即抛错、不会走到此处，
        // 故对该桩放宽严格性（lenient），避免无辜触发 UnnecessaryStubbingException。
        lenient().when(arrangementRepository.findByEventIdAndAthleteId(1L, 1L)).thenReturn(Optional.empty());
    }

    // ==================== R-2/R-3 回归（无表头固定列序） ====================

    /** R-2：DNF 标记应被识别为独立状态 dnf，而非静默当有效成绩（避免被算入计分/排在冠军前） */
    @Test
    void import_dnfTokenSetsNonFinishStatus() {
        stubBasics();

        listener.invoke(row("M100", "1001", "DNF"), ctx(0));
        List<Result> saved = flushAndGetSaved();

        assertEquals(1, saved.size());
        assertEquals("dnf", saved.get(0).getStatus(), "DNF 应标记为非完赛状态");
        assertNull(saved.get(0).getTimeSeconds(), "非完赛不应有成绩秒数");
        assertEquals(0, listener.getErrorCount(), "合法非完赛标记不应计入错误");
    }

    /** R-2：DNS / DSQ 同样应被识别（大小写不敏感）。
     *  每个 token 用独立监听器，避免「同一运动员同项目不同成绩」被导入内冲突判定拦截（那是另一个关注点）。 */
    @Test
    void import_dnsAndDsqTokensRecognized() {
        String[] tokens = {"dns", "DSQ"};
        String[] statuses = {"dns", "dsq"};
        for (int i = 0; i < tokens.length; i++) {
            ScoreDataListener l = new ScoreDataListener(resultRepository, eventRepository,
                    athleteRepository, arrangementRepository);
            stubBasics();
            l.invoke(row("M100", "1001", tokens[i]), ctx(0));
            List<Result> saved = flushAndGetSaved(l);
            assertEquals(0, l.getErrorCount(), "合法非完赛标记不应报错: " + tokens[i]);
            assertEquals(1, saved.size());
            assertEquals(statuses[i], saved.get(0).getStatus());
        }
    }

    /** 回归：正常数值成绩仍按 valid 落库且 timeSeconds 正确解析 */
    @Test
    void import_numericTimeStaysValid() {
        stubBasics();

        listener.invoke(row("M100", "1001", "12.34"), ctx(0));
        List<Result> saved = flushAndGetSaved();

        assertEquals(1, saved.size());
        assertEquals("valid", saved.get(0).getStatus());
        assertEquals(12.34, saved.get(0).getTimeSeconds(), 0.001);
        assertEquals(0, listener.getErrorCount());
    }

    /**
     * R-3：畸形时间（非空白、非 DNF 标记、但无法解析为数值，如 "12.5.3"）必须计入错误，
     * 而绝不能静默以 valid 落库（旧实现 parseTimeToSeconds 返回 null 后仍以 valid 入库）。
     */
    @Test
    void import_malformedTimeIsErrorNotSilentValid() {
        stubBasics();
        listener.invoke(row("M100", "1001", "12.5.3"), ctx(0));

        assertEquals(1, listener.getErrorCount(), "畸形时间应计入错误而非静默 valid");
        assertEquals(0, listener.getSuccessCount(), "畸形时间不应产生成功落库");
        assertTrue(listener.getErrors().get(0).get("message").toString().contains("格式非法"),
                "错误信息应提示成绩格式非法");
    }

    /** R-3：非正或超过上限（>100000）的时间一律计入错误，杜绝脏数据入库 */
    @Test
    void import_outOfRangeTimeIsError() {
        stubBasics();
        listener.invoke(row("M100", "1001", "0"), ctx(0));
        listener.invoke(row("M100", "1001", "-5"), ctx(1));
        listener.invoke(row("M100", "1001", "200000"), ctx(2));

        assertEquals(3, listener.getErrorCount(), "非正/超限时间应全部计入错误");
        assertEquals(0, listener.getSuccessCount());
    }

    /** R-3：边界值（>0 的正整数秒、恰好 100000 秒上限）应被正常接受为 valid 成绩。
     *  注意：同一运动员+项目导入不同成绩会被「导入内冲突判定」拦截（那是另一个关注点），
     *  故这里每个边界值用独立监听器，隔离「时间接受」这一行为本身。 */
    @Test
    void import_boundaryTimeAccepted() {
        String[] times = {"0.01", "100000", "99999.99"};
        double[] expected = {0.01, 100000.0, 99999.99};
        for (int i = 0; i < times.length; i++) {
            ScoreDataListener l = new ScoreDataListener(resultRepository, eventRepository,
                    athleteRepository, arrangementRepository);
            stubBasics();
            l.invoke(row("M100", "1001", times[i]), ctx(0));
            List<Result> saved = flushAndGetSaved(l);
            assertEquals(0, l.getErrorCount(), "合法边界时间不应报错: " + times[i]);
            assertEquals(1, l.getSuccessCount(), "边界时间应成功落库: " + times[i]);
            assertEquals(1, saved.size());
            assertEquals(expected[i], saved.get(0).getTimeSeconds(), 0.0001, "边界时间解析值错误: " + times[i]);
        }
    }

    // ==================== 表头驱动（2026-09-27 重写） ====================

    /** 表头乱序：列序与模板不同也能按表头别名正确落点（旧实现按固定 index 会错位） */
    @Test
    @DisplayName("表头乱序：按别名定位列，表头行不产生错误")
    void headerDriven_shuffledColumns() {
        stubBasics();

        LinkedHashMap<Integer, String> head = new LinkedHashMap<>();
        head.put(0, "运动员姓名");
        head.put(1, "成绩");
        head.put(2, "项目编码");
        head.put(3, "运动员号码");
        LinkedHashMap<Integer, String> data = new LinkedHashMap<>();
        data.put(0, "张三");
        data.put(1, "12.34");
        data.put(2, "M100");
        data.put(3, "1001");

        listener.invoke(head, ctx(0));
        listener.invoke(data, ctx(1));
        List<Result> saved = flushAndGetSaved();

        assertEquals(1, listener.getSuccessCount(), "表头行应被消费，仅数据行计数");
        assertEquals(0, listener.getErrorCount());
        assertEquals(1, saved.size());
        assertEquals("valid", saved.get(0).getStatus());
        assertEquals(12.34, saved.get(0).getTimeSeconds(), 0.001);
    }

    /** 回归（用户报障 2026-09-26）：真实成绩表带「年级/班级」文本列（高一年级/高一1班）——
     *  旧实现第 4/5 列是 Integer 字段，「高一」直接 NumberFormatException 炸掉整次导入；
     *  新实现全 String 读取 + 多余列自动忽略。 */
    @Test
    @DisplayName("年级/班级文本列：自动忽略，不再触发 Integer 转换异常")
    void headerDriven_extraGradeClassColumnsTolerated() {
        stubBasics();

        LinkedHashMap<Integer, String> head = new LinkedHashMap<>();
        head.put(0, "项目编码");
        head.put(1, "运动员号码");
        head.put(2, "运动员姓名");
        head.put(3, "年级");
        head.put(4, "班级");
        head.put(5, "成绩");
        LinkedHashMap<Integer, String> data = new LinkedHashMap<>();
        data.put(0, "M100");
        data.put(1, "1001");
        data.put(2, "张三");
        data.put(3, "高一年级");
        data.put(4, "高一1班");
        data.put(5, "12.34");

        listener.invoke(head, ctx(0));
        listener.invoke(data, ctx(1));
        List<Result> saved = flushAndGetSaved();

        assertEquals(0, listener.getErrorCount(), "年级/班级文本列不应产生任何错误");
        assertEquals(1, saved.size());
        assertEquals(12.34, saved.get(0).getTimeSeconds(), 0.001);
    }

    /** 组别/道次填了文本（「高一」「A」）：容错解析为 null 后回退编排信息，行本身不报错 */
    @Test
    @DisplayName("文本组别/道次：解析为 null 并回退编排信息的组次/道次")
    void textHeatLane_fallsBackToArrangement() {
        stubBasics();
        when(arrangementRepository.findByEventIdAndAthleteId(1L, 1L))
                .thenReturn(Optional.of(com.sports.entity.arrange.Arrangement.builder()
                        .heat(2).lane(5).build()));

        LinkedHashMap<Integer, String> data = row("M100", "1001", "12.34");
        data.put(4, "高一");
        data.put(5, "A");
        listener.invoke(data, ctx(0));
        List<Result> saved = flushAndGetSaved();

        assertEquals(0, listener.getErrorCount(), "文本组别/道次不应报错");
        assertEquals(1, saved.size());
        assertEquals(2, saved.get(0).getHeat(), "组别应回退为编排信息的组次");
        assertEquals(5, saved.get(0).getLane(), "道次应回退为编排信息的道次");
    }

    /** 组别/道次含数字的文本（「第3组」「4道」）应提取数字 */
    @Test
    @DisplayName("「第3组」「4道」：提取数字作为组别/道次")
    void heatLaneNumericTokens_extracted() {
        stubBasics();

        LinkedHashMap<Integer, String> data = row("M100", "1001", "12.34");
        data.put(4, "第3组");
        data.put(5, "4道");
        listener.invoke(data, ctx(0));
        List<Result> saved = flushAndGetSaved();

        assertEquals(0, listener.getErrorCount());
        assertEquals(1, saved.size());
        assertEquals(3, saved.get(0).getHeat());
        assertEquals(4, saved.get(0).getLane());
        verify(arrangementRepository, never()).findByEventIdAndAthleteId(anyLong(), anyLong());
    }

    /** 项目列填的是名称（「100米」）而非编码（「100M」）：编码未命中时按名称兜底 */
    @Test
    @DisplayName("项目名称兜底：编码未命中时按项目名称匹配")
    void eventByNameFallback() {
        Event e = Event.builder().id(1L).code("M100").name("100米").build();
        when(eventRepository.findByNameAndIsEnabledTrue("100米")).thenReturn(Optional.of(e));
        Athlete a = Athlete.builder().id(1L).name("张三").number("1001").build();
        when(athleteRepository.findByNumber("1001")).thenReturn(Optional.of(a));
        when(resultRepository.findByEventIdAndAthleteId(1L, 1L)).thenReturn(Optional.empty());
        lenient().when(arrangementRepository.findByEventIdAndAthleteId(1L, 1L)).thenReturn(Optional.empty());

        listener.invoke(row("100米", "1001", "12.34"), ctx(0));
        List<Result> saved = flushAndGetSaved();

        assertEquals(0, listener.getErrorCount());
        assertEquals(1, saved.size());
        assertEquals("M100", saved.get(0).getEvent().getCode());
    }

    /** 无号码列、只有「学号」列：按学号匹配运动员 */
    @Test
    @DisplayName("学号列：号码缺失时按学号匹配运动员")
    void studentIdColumn_usedForAthleteMatch() {
        Event e = Event.builder().id(1L).code("M100").name("100米").build();
        when(eventRepository.findByCode("M100")).thenReturn(Optional.of(e));
        Athlete a = Athlete.builder().id(1L).name("张三").number("1001").studentId("2024001").build();
        when(athleteRepository.findByStudentId("2024001")).thenReturn(Optional.of(a));
        when(resultRepository.findByEventIdAndAthleteId(1L, 1L)).thenReturn(Optional.empty());
        lenient().when(arrangementRepository.findByEventIdAndAthleteId(1L, 1L)).thenReturn(Optional.empty());

        LinkedHashMap<Integer, String> head = new LinkedHashMap<>();
        head.put(0, "项目编码");
        head.put(1, "学号");
        head.put(3, "成绩");
        LinkedHashMap<Integer, String> data = new LinkedHashMap<>();
        data.put(0, "M100");
        data.put(1, "2024001");
        data.put(3, "12.34");

        listener.invoke(head, ctx(0));
        listener.invoke(data, ctx(1));
        List<Result> saved = flushAndGetSaved();

        assertEquals(0, listener.getErrorCount());
        assertEquals(1, saved.size());
        assertEquals("张三", saved.get(0).getAthlete().getName());
    }

    /** 安全网：单元格级转换异常只跳过该行并计错，绝不允许再炸掉整次导入（旧实现痛点） */
    @Test
    @DisplayName("单元格转换异常：跳行计错而非中止导入")
    void convertException_skipsRowNotWholeImport() throws Exception {
        listener.onException(new ExcelDataConvertException(0, 4, null, null, "boom"), null);

        assertEquals(1, listener.getErrorCount(), "转换异常应计错一次");
        assertEquals(0, listener.getSuccessCount());
        assertTrue(listener.getErrors().get(0).get("message").toString().contains("已跳过该行"));
    }
}
