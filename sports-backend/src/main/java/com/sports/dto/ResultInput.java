package com.sports.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 成绩录入请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResultInput {

    @NotNull(message = "运动员ID不能为空")
    private Long athleteId;

    @NotBlank(message = "成绩不能为空")
    private String rawTime;

    /** 风速（径赛可选） */
    private Double windSpeed;

    /** 备注 */
    private String remark;
}

/**
 * 批量成绩录入请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
class BatchResultInput {

    @NotNull(message = "项目ID不能为空")
    private Long eventId;

    private Integer heat;

    @NotNull
    private java.util.List<ResultInput> results;
}
