package com.sports.service.meet;

import com.sports.common.util.GradeMeetUtil;
import com.sports.entity.athlete.Athlete;
import com.sports.repository.athlete.AthleteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 届相关维护：升级与毕业生处理。
 *
 * <p>「升级」由 {@link GradeMeetUtil#currentGradeDisplay} 在读取时按当前届年份递归推导
 * （自然升级，每年 +1），不落库、无需批量改数据；本服务负责<b>毕业生</b>这一需要落库的态：
 * 当前届年份 ≥ 毕业年份即标记 {@code graduated=true}。提供幂等重算，可在切换当前届后调用。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MeetMaintenanceService {

    private final AthleteRepository athleteRepository;
    private final MeetService meetService;

    /**
     * 依据当前届年份重算全部运动员的毕业生标记（幂等：仅对变化项写库）。
     *
     * @return 实际发生变更的运动员数
     */
    @Transactional
    public int recomputeGraduation() {
        int year = meetService.getActiveOrCreateDefault().getYear();
        int changed = 0;
        for (Athlete a : athleteRepository.findAll()) {
            boolean g = GradeMeetUtil.isGraduated(a.getGraduateYear(), year);
            if (a.getGraduated() == null || a.getGraduated() != g) {
                a.setGraduated(g);
                athleteRepository.save(a);
                changed++;
            }
        }
        log.info("[meet-maint] 毕业生标记重算完成：当前届年份={}，变更 {} 人", year, changed);
        return changed;
    }
}
