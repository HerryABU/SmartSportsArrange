package com.sports.dto.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

/**
 * 成绩 Excel 导入/导出模型
 */
@Data
public class ScoreExcelModel {

    @ExcelProperty(value = "项目编码", index = 0)
    private String eventCode;

    @ExcelProperty(value = "运动员号码", index = 1)
    private String athleteNumber;

    @ExcelProperty(value = "运动员姓名", index = 2)
    private String athleteName;

    @ExcelProperty(value = "成绩", index = 3)
    private String rawTime;

    @ExcelProperty(value = "组别", index = 4)
    private Integer heat;

    @ExcelProperty(value = "道次", index = 5)
    private Integer lane;

    @ExcelProperty(value = "风速", index = 6)
    private String windSpeed;

    @ExcelProperty(value = "备注", index = 7)
    private String remark;
}
