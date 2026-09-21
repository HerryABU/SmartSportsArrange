package com.sports.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 编排请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArrangeRequest {

    @NotBlank(message = "年级不能为空")
    private String grade;

    @NotBlank(message = "性别不能为空")
    private String gender;

    @NotNull(message = "跑道数不能为空")
    private Integer lanes;

    /** 编排规则配置：含 L1 款型 l1Rule（class/snake）与其它规则开关 */
    private Map<String, Object> ruleConfig;
}
