package com.sports.support;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Excel 测试数据「动态生成器」——测试用例不再硬编码二进制/文本样例，而是按表头 + 行数据
 * 现场生成 .xlsx 字节。与生产端 {@code ExcelService.getTemplate} 使用同一 EasyExcel 写库，
 * 保证「测试生成的文件」与「真实导入的文件」结构一致（同样的 表头行 + 数据行）。
 *
 * <p>约定：第 0 行为表头（与 {@code importWithMapping} 的 hasHeader=true、startRow=1 对齐）。</p>
 */
public final class ExcelTestDataFactory {

    private ExcelTestDataFactory() {
    }

    /** 通用：按表头与行数据生成 xlsx 字节（第 0 行为表头）。 */
    public static byte[] xlsx(List<String> headers, List<List<String>> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<List<String>> head = new ArrayList<>();
        for (String h : headers) head.add(List.of(h));
        try (ExcelWriter writer = EasyExcel.write(out).build()) {
            WriteSheet sheet = EasyExcel.writerSheet(0, "数据").head(head).build();
            writer.write(rows, sheet);
        }
        return out.toByteArray();
    }

    /** 便捷：表头 + 数组行。 */
    public static byte[] xlsx(List<String> headers, String[][] rows) {
        List<List<String>> list = new ArrayList<>();
        for (String[] r : rows) list.add(Arrays.asList(r));
        return xlsx(headers, list);
    }

    /** 一个 Sheet 的规格：表名 + 表头 + 行数据（多表导入用例用）。 */
    public record SheetSpec(String name, List<String> headers, List<List<String>> rows) {

        public static SheetSpec of(String name, List<String> headers, String[][] rows) {
            List<List<String>> list = new ArrayList<>();
            for (String[] r : rows) list.add(Arrays.asList(r));
            return new SheetSpec(name, headers, list);
        }
    }

    /**
     * 多 Sheet 工作簿：一次生成含多个<b>具名</b> Sheet 的 xlsx（多表导入用例用）。
     *
     * <p>Sheet 名带业务含义（如「年级表」「班级表」「全名单」），因为多表导入正是靠表名 + 表头指纹来判定类型。</p>
     */
    public static byte[] xlsxMulti(List<SheetSpec> sheets) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelWriter writer = EasyExcel.write(out).build()) {
            int idx = 0;
            for (SheetSpec s : sheets) {
                List<List<String>> head = new ArrayList<>();
                for (String h : s.headers()) head.add(List.of(h));
                WriteSheet ws = EasyExcel.writerSheet(idx++, s.name()).head(head).build();
                writer.write(s.rows(), ws);
            }
        }
        return out.toByteArray();
    }

    // ==================== 与各导入模板列序严格一致的类型化生成器 ====================

    /** 全名单表（5列：年级/班级/姓名/学号/性别）。 */
    public static byte[] roster(String[][] rows) {
        return xlsx(List.of("年级", "班级", "姓名", "学号", "性别"), rows);
    }

    /** 报名表（7列：年级/班级/姓名/学号/性别/项目/组号）。 */
    public static byte[] signup(String[][] rows) {
        return xlsx(List.of("年级", "班级", "姓名", "学号", "性别", "项目", "组号"), rows);
    }

    /** 运动项目表（7列：项目代码/项目名称/每组人数/每批组数/项目类型/场地号/每批所需时间(分)）。 */
    public static byte[] eventsimple(String[][] rows) {
        return xlsx(List.of("项目代码", "项目名称", "每组人数", "每批组数", "项目类型", "场地号", "每批所需时间(分)"), rows);
    }
}
