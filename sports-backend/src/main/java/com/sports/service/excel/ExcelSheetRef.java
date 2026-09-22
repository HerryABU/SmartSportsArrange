package com.sports.service.excel;

/**
 * 工作簿里的一个 Sheet（序号 + 真实名称）。
 *
 * <p><b>为什么要带真实名称</b>：多表导入靠「Sheet 名」判定该表是什么（如「年级表」「班级表」「高一年级」），
 * 旧的预览逻辑只能用 {@code Sheet1/Sheet2} 这样的序号占位名，无法支撑按表名分派。</p>
 */
public record ExcelSheetRef(int index, String name) {
}
