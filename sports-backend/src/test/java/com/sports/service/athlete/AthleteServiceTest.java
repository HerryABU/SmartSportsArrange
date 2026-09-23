package com.sports.service.athlete;

import com.sports.entity.athlete.Athlete;
import com.sports.entity.clazz.ClassInfo;
import com.sports.entity.registration.Registration;
import com.sports.entity.result.Result;
import com.sports.repository.arrange.ArrangementRepository;
import com.sports.repository.athlete.AthleteRepository;
import com.sports.repository.clazz.ClassInfoRepository;
import com.sports.repository.registration.RegistrationRepository;
import com.sports.repository.result.ResultRepository;
import com.sports.service.excel.ExcelService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * 运动员批量删除 + 条件约束测试。
 * 重点覆盖：被成绩(Result)/报名(Registration)/编排(Arrangement) 引用的运动员必须跳过并报告原因，
 * 未被引用的运动员才软删除。
 */
@ExtendWith(MockitoExtension.class)
class AthleteServiceTest {

    @Mock private AthleteRepository athleteRepository;
    @Mock private ClassInfoRepository classInfoRepository;
    @Mock private ExcelService excelService;
    @Mock private NumberRuleService numberRuleService;
    @Mock private ResultRepository resultRepository;
    @Mock private RegistrationRepository registrationRepository;
    @Mock private ArrangementRepository arrangementRepository;

    @InjectMocks private AthleteService athleteService;

    private Athlete athlete(Long id, String name) {
        Athlete a = new Athlete();
        a.setId(id);
        a.setName(name);
        return a;
    }

    @Test
    void batchDeleteSkipsReferencedAthletes() {
        Athlete a1 = athlete(1L, "张三");   // 被成绩引用
        Athlete a2 = athlete(2L, "李四");   // 被报名引用
        Athlete a3 = athlete(3L, "王五");   // 被编排引用
        Athlete a4 = athlete(4L, "赵六");   // 无引用，可删

        Result r1 = new Result();
        r1.setAthlete(a1);
        Registration reg2 = new Registration();
        reg2.setAthlete(a2);

        when(resultRepository.findValidByAthleteIdIn(anyList())).thenReturn(List.of(r1));
        when(registrationRepository.findActiveByAthleteIdIn(anyList())).thenReturn(List.of(reg2));
        when(arrangementRepository.findAthleteIdsIn(anyList())).thenReturn(List.of(3L));

        when(athleteRepository.findById(1L)).thenReturn(Optional.of(a1));
        when(athleteRepository.findById(2L)).thenReturn(Optional.of(a2));
        when(athleteRepository.findById(3L)).thenReturn(Optional.of(a3));
        when(athleteRepository.findById(4L)).thenReturn(Optional.of(a4));
        when(athleteRepository.save(any(Athlete.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> res = athleteService.batchDelete(List.of(1L, 2L, 3L, 4L));

        assertEquals(4, res.get("total"));
        assertEquals(1, res.get("success"), "仅赵六（无引用）应被删除");
        assertEquals(3, res.get("skipped"), "被三张表引用的 3 人应被跳过");

        @SuppressWarnings("unchecked")
        List<String> deleted = (List<String>) res.get("deleted");
        assertTrue(deleted.contains("赵六"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) res.get("errors");
        assertEquals(3, errors.size());
        boolean hasScore = errors.stream().anyMatch(e -> ((String) e.get("message")).contains("成绩"));
        boolean hasReg = errors.stream().anyMatch(e -> ((String) e.get("message")).contains("报名"));
        boolean hasArr = errors.stream().anyMatch(e -> ((String) e.get("message")).contains("编排"));
        assertTrue(hasScore && hasReg && hasArr, "跳过原因应分别标明 成绩/报名/编排");
    }

    @Test
    void batchDeleteAllDeletableWhenNoReferences() {
        Athlete a1 = athlete(10L, "可删甲");
        Athlete a2 = athlete(11L, "可删乙");
        when(resultRepository.findValidByAthleteIdIn(anyList())).thenReturn(List.of());
        when(registrationRepository.findActiveByAthleteIdIn(anyList())).thenReturn(List.of());
        when(arrangementRepository.findAthleteIdsIn(anyList())).thenReturn(List.of());
        when(athleteRepository.findById(10L)).thenReturn(Optional.of(a1));
        when(athleteRepository.findById(11L)).thenReturn(Optional.of(a2));
        when(athleteRepository.save(any(Athlete.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> res = athleteService.batchDelete(List.of(10L, 11L));
        assertEquals(2, res.get("total"));
        assertEquals(2, res.get("success"));
        assertEquals(0, res.get("skipped"));
    }

    @Test
    void deleteAllDeletesEverythingDeletableAndSkipsReferenced() {
        Athlete a1 = athlete(1L, "张三");   // 被报名引用 → 跳过
        Athlete a2 = athlete(2L, "李四");   // 可删
        Athlete a3 = athlete(3L, "王五");   // 可删

        when(athleteRepository.findAll()).thenReturn(List.of(a1, a2, a3));
        when(resultRepository.findValidByAthleteIdIn(anyList())).thenReturn(List.of());
        Registration reg = new Registration();
        reg.setAthlete(a1);
        when(registrationRepository.findActiveByAthleteIdIn(anyList())).thenReturn(List.of(reg));
        when(arrangementRepository.findAthleteIdsIn(anyList())).thenReturn(List.of());
        when(athleteRepository.findById(1L)).thenReturn(Optional.of(a1));
        when(athleteRepository.findById(2L)).thenReturn(Optional.of(a2));
        when(athleteRepository.findById(3L)).thenReturn(Optional.of(a3));
        when(athleteRepository.save(any(Athlete.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> res = athleteService.deleteAll();

        assertEquals(Boolean.TRUE, res.get("all"), "全部删除须带 all=true 标记");
        assertEquals(3, res.get("total"));
        assertEquals(2, res.get("success"), "无引用的 2 人应被删除");
        assertEquals(1, res.get("skipped"), "被报名的 1 人应被跳过");
    }

    @Test
    void createResolvesClassFromTypedName_andAutoCreatesWhenMissing() {
        Athlete a = new Athlete();
        a.setName("测试同学");
        a.setGrade("高一年级");
        a.setClassNameInput("高一9班");   // 前端直接输入的班级名（系统中不存在）

        when(classInfoRepository.findByName("高一9班")).thenReturn(Optional.empty());
        when(classInfoRepository.save(any(ClassInfo.class))).thenAnswer(inv -> {
            ClassInfo c = inv.getArgument(0);
            c.setId(99L);
            return c;
        });
        when(athleteRepository.save(any(Athlete.class))).thenAnswer(inv -> inv.getArgument(0));

        Athlete saved = athleteService.create(a);

        assertNotNull(saved.getClassInfo(), "应把输入的班级名解析成 ClassInfo");
        assertEquals("高一9班", saved.getClassInfo().getName());
        // 模糊年级：输入「高一年级」按规范短称「高一」落库（与库内既有口径统一）
        assertEquals("高一", saved.getClassInfo().getGrade(), "新建班级应带上归一化后的年级");
    }
}
