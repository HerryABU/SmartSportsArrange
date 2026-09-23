package com.sports.config;

import com.sports.common.util.Grades;
import com.sports.entity.athlete.Athlete;
import com.sports.entity.clazz.ClassInfo;
import com.sports.repository.athlete.AthleteRepository;
import com.sports.repository.clazz.ClassInfoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 模糊年级 · 存量数据一次性归一化（幂等）。
 *
 * <p>历史库里同一届可能并存多种写法：「高一」「高一年级」「10年级」。
 * 本类在启动时把它们统一成规范短称（{@link Grades#norm}，即「高一」），并顺手补全
 * {@code ClassInfo.gradeOrder} 空缺；已经规范的记录<strong>不会被写回</strong>，所以重复启动是空操作。</p>
 *
 * <p>为什么需要：编排/分班/号码簿等处大量按年级做等价比较（{@link Grades#same}），
 * 但「展示给人看」和「按年级 SQL 精确筛」仍以库内字符串为准；把库内值收敛到一种写法，
 * 才能保证「输入哪种写法都搜得到」。</p>
 *
 * <p>注意：只收敛能解析出年级的写法（「实验班」「不分年级」这类原样保留）。</p>
 */
@Slf4j
@Component
@Order(30)
@RequiredArgsConstructor
public class GradeNormalizeInitializer implements CommandLineRunner {

    private final ClassInfoRepository classInfoRepository;
    private final AthleteRepository athleteRepository;

    @Override
    @Transactional
    public void run(String... args) {
        int classFixed = 0;
        int athleteFixed = 0;

        for (ClassInfo c : classInfoRepository.findAll()) {
            boolean dirty = false;
            String norm = Grades.norm(c.getGrade());
            if (norm != null && !norm.equals(c.getGrade())) {
                c.setGrade(norm);
                dirty = true;
            }
            int order = Grades.order(norm);
            if (order > 0 && (c.getGradeOrder() == null || c.getGradeOrder() == 0)) {
                c.setGradeOrder(order);   // 只补空缺，不覆盖用户已设的排序
                dirty = true;
            }
            if (dirty) {
                classInfoRepository.save(c);
                classFixed++;
            }
        }

        for (Athlete a : athleteRepository.findAll()) {
            String norm = Grades.norm(a.getGrade());
            if (norm != null && !norm.equals(a.getGrade())) {
                a.setGrade(norm);
                athleteRepository.save(a);
                athleteFixed++;
            }
        }

        if (classFixed + athleteFixed > 0) {
            log.info("[db-grade] 模糊年级归一化完成：班级 {} 条、运动员 {} 条", classFixed, athleteFixed);
        } else {
            log.info("[db-grade] 模糊年级：无需归一化（库内写法已一致）");
        }
    }
}
