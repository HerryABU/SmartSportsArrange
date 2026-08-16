package com.sports.dto.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

/**
 * 报名 Excel 导入/导出模型
 */
@Data
public class RegistrationExcelModel {

    @ExcelProperty(value = "项目编码", index = 0)
    private String eventCode;

    @ExcelProperty(value = "运动员号码", index = 1)
    private String athleteNumber;

    @ExcelProperty(value = "运动员姓名", index = 2)
    private String athleteName;

    @ExcelProperty(value = "年级", index = 3)
    private String grade;

    @ExcelProperty(value = "班级", index = 4)
    private String className;

    @ExcelProperty(value = "备注", index = 5)
    private String remark;
}
