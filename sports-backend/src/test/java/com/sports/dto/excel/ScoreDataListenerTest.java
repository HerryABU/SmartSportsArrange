package com.sports.dto.excel;

import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.metadata.holder.ReadRowHolder;
import com.alibaba.excel.read.metadata.holder.ReadSheetHolder;
import com.sports.entity.*;
import com.sports.repository.ArrangementRepository;
import com.sports.repository.AthleteRepository;
import com.sports.repository.EventRepository;
import com.sports.repository.ResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 成绩导入监听器测试。
 * 覆盖：R-2 非完赛标记（DNF/DNS/DSQ）识别为独立状态、正常数值成绩不受影响；
 *      R-3 畸形/越界时间不再静默以 valid 落库，而是计入 errors。
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

    private AnalysisContext ctx() {
        AnalysisContext ctx = mock(AnalysisContext.class);
        ReadRowHolder row = mock(ReadRowHolder.class);
        when(ctx.readRowHolder()).thenReturn(row);
        when(row.getRowIndex()).thenReturn(0);
        when(ctx.readSheetHolder()).thenReturn(null);
        return ctx;
    }

    private ScoreExcelModel model(String rawTime) {
        ScoreExcelModel m = new ScoreExcelModel();
        m.setEventCode("M100");
        m.setAthleteNumber("1001");
        m.setRawTime(rawTime);
        return m;
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

    /** R-2：DNF 标记应被识别为独立状态 dnf，而非静默当有效成绩（避免被算入计分/排在冠军前） */
    @Test
    void import_dnfTokenSetsNonFinishStatus() {
        stubBasics();
        ArgumentCaptor<Result> cap = ArgumentCaptor.forClass(Result.class);
        when(resultRepository.save(cap.capture())).thenAnswer(inv -> inv.getArgument(0));

        listener.invoke(model("DNF"), ctx());

        assertEquals("dnf", cap.getValue().getStatus(), "DNF 应标记为非完赛状态");
        assertNull(cap.getValue().getTimeSeconds(), "非完赛不应有成绩秒数");
        assertEquals(0, listener.getErrorCount(), "合法非完赛标记不应计入错误");
    }

    /** R-2：DNS / DSQ 同样应被识别（大小写不敏感） */
    @Test
    void import_dnsAndDsqTokensRecognized() {
        stubBasics();
        ArgumentCaptor<Result> cap = ArgumentCaptor.forClass(Result.class);
        when(resultRepository.save(cap.capture())).thenAnswer(inv -> inv.getArgument(0));

        listener.invoke(model("dns"), ctx());
        listener.invoke(model("DSQ"), ctx());

        List<Result> saved = cap.getAllValues();
        assertEquals("dns", saved.get(0).getStatus());
        assertEquals("dsq", saved.get(1).getStatus());
    }

    /** 回归：正常数值成绩仍按 valid 落库且 timeSeconds 正确解析 */
    @Test
    void import_numericTimeStaysValid() {
        stubBasics();
        ArgumentCaptor<Result> cap = ArgumentCaptor.forClass(Result.class);
        when(resultRepository.save(cap.capture())).thenAnswer(inv -> inv.getArgument(0));

        listener.invoke(model("12.34"), ctx());

        assertEquals("valid", cap.getValue().getStatus());
        assertEquals(12.34, cap.getValue().getTimeSeconds(), 0.001);
        assertEquals(0, listener.getErrorCount());
    }

    /**
     * R-3：畸形时间（非空白、非 DNF 标记、但无法解析为数值，如 "12.5.3"）必须计入错误，
     * 而绝不能静默以 valid 落库（旧实现 parseTimeToSeconds 返回 null 后仍以 valid 入库）。
     */
    @Test
    void import_malformedTimeIsErrorNotSilentValid() {
        stubBasics();
        listener.invoke(model("12.5.3"), ctx());

        assertEquals(1, listener.getErrorCount(), "畸形时间应计入错误而非静默 valid");
        assertEquals(0, listener.getSuccessCount(), "畸形时间不应产生成功落库");
        assertTrue(listener.getErrors().get(0).get("message").toString().contains("格式非法"),
                "错误信息应提示成绩格式非法");
    }

    /** R-3：非正或超过上限（>100000）的时间一律计入错误，杜绝脏数据入库 */
    @Test
    void import_outOfRangeTimeIsError() {
        stubBasics();
        listener.invoke(model("0"), ctx());
        listener.invoke(model("-5"), ctx());
        listener.invoke(model("200000"), ctx());

        assertEquals(3, listener.getErrorCount(), "非正/超限时间应全部计入错误");
        assertEquals(0, listener.getSuccessCount());
    }

    /** R-3：边界值（>0 的正整数秒、恰好 100000 秒上限）应被正常接受为 valid 成绩 */
    @Test
    void import_boundaryTimeAccepted() {
        stubBasics();
        ArgumentCaptor<Result> cap = ArgumentCaptor.forClass(Result.class);
        when(resultRepository.save(cap.capture())).thenAnswer(inv -> inv.getArgument(0));

        listener.invoke(model("0.01"), ctx());
        listener.invoke(model("100000"), ctx());
        listener.invoke(model("99999.99"), ctx());

        assertEquals(0, listener.getErrorCount(), "合法边界时间不应报错");
        assertEquals(3, listener.getSuccessCount());
        List<Result> saved = cap.getAllValues();
        assertEquals(0.01, saved.get(0).getTimeSeconds(), 0.0001);
        assertEquals(100000.0, saved.get(1).getTimeSeconds(), 0.0001);
    }
}
