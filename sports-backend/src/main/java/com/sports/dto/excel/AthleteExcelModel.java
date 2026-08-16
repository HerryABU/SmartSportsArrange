package com.sports.dto.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

/**
 * 运动员 Excel 导入/导出模型
 */
@Data
public class AthleteExcelModel {

    @ExcelProperty(value = "姓名", index = 0)
    private String name;

    @ExcelProperty(value = "性别", index = 1)
    private String gender;

    @ExcelProperty(value = "年级", index = 2)
    private String grade;

    @ExcelProperty(value = "班级", index = 3)
    private String className;

    @ExcelProperty(value = "学号", index = 4)
    private String studentId;

    @ExcelProperty(value = "号码布编号", index = 5)
    private String number;

    @ExcelProperty(value = "身份证号", index = 6)
    private String idCard;

    @ExcelProperty(value = "出生日期", index = 7)
    private String birthDate;

    @ExcelProperty(value = "紧急联系人", index = 8)
    private String emergencyContact;

    @ExcelProperty(value = "紧急联系电话", index = 9)
    private String emergencyPhone;

    @ExcelProperty(value = "健康状况", index = 10)
    private String healthStatus;

    @ExcelProperty(value = "备注", index = 11)
    private String remark;
}
