package com.sports.schedule.opt.bandit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UCB1 多臂老虎机的验证：未试用优先、均值与探索的平衡、奖励驱动的偏好转移。
 */
@DisplayName("UCB1 自适应算子选择器")
class OperatorBanditTest {

    @Test
    @DisplayName("未试用的臂按顺序优先被选中（每臂至少一次）")
    void untriedArmsSelectedFirst() {
        OperatorBandit bandit = new OperatorBandit(List.of("甲", "乙", "丙"));
        assertEquals(0, bandit.select());
        bandit.reward(0, 1.0);
        assertEquals(1, bandit.select());
        bandit.reward(1, 0.0);
        assertEquals(2, bandit.select());
        bandit.reward(2, 0.5);
        assertEquals(3, bandit.names().size());
    }

    @Test
    @DisplayName("高收益臂获得更多使用次数——奖励驱动偏好")
    void rewardShiftsSelection() {
        OperatorBandit bandit = new OperatorBandit(List.of("弱", "强"));
        // 预热：两臂各试一次
        bandit.select();
        bandit.reward(0, 0.0);
        bandit.select();
        bandit.reward(1, 1.0);
        // 之后的选择应明显偏向「强」
        int strongCount = 0;
        for (int i = 0; i < 50; i++) {
            int arm = bandit.select();
            bandit.reward(arm, arm == 1 ? 1.0 : 0.0);
            if (arm == 1) strongCount++;
        }
        assertTrue(strongCount >= 40, "50 次选择中「强」臂应被选中 ≥40 次，实际 " + strongCount);
    }

    @Test
    @DisplayName("均值相同、次数少的臂更具探索优势（UCB 探索项）")
    void explorationFavorsUndertriedArm() {
        OperatorBandit bandit = new OperatorBandit(List.of("多", "少"));
        bandit.select();
        bandit.reward(0, 0.5);
        bandit.select();
        bandit.reward(1, 0.5);
        // 甲均值相同但已多用一次 → 探索项更小 → 下一次应选「少」
        for (int i = 0; i < 9; i++) {
            bandit.select();
            bandit.reward(0, 0.5);
        }
        assertEquals(1, bandit.select(), "同等均值下应探索使用次数少的臂");
    }

    @Test
    @DisplayName("空算子池与越界奖励被拒绝")
    void rejectsInvalidUsage() {
        assertThrows(IllegalArgumentException.class, () -> new OperatorBandit(List.of()));
        OperatorBandit bandit = new OperatorBandit(List.of("唯一"));
        assertThrows(IllegalArgumentException.class, () -> bandit.reward(5, 1.0));
        assertTrue(Double.isNaN(bandit.mean(0)), "未试用臂的均值为 NaN");
    }
}
