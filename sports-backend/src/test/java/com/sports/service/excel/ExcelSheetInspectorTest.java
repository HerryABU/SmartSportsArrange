package com.sports.service.excel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可视化预览取数（{@link ExcelSheetInspector}）单测。
 *
 * <p>前端「可视化预览」按钮走的就是 {@code /excel/multi/sheet-data}，后端这套取数只要有一处
 * 吞掉异常（例如 Sheet 序号越界、表头行读不出来），用户看到的不是报错，而是一句
 * 「这张表暂时没有可显示的数据行」——功能等于不可用。这里用<b>真实的多表工作簿</b>
 * （9 个 Sheet，含「项目表（表格2）」这种 19 列表）把这条链路钉住。</p>
 */
@DisplayName("多表导入：可视化预览取数")
class ExcelSheetInspectorTest {

    /** 测试工作簿位置（由 _trash/gen_template.py 生成；Maven 的 user.dir 是 sports-backend）。 */
    private static final String WORKBOOK = "../_trash/multi_template_test.xlsx";

    /** 「项目表（表格2）」在测试工作簿里是第 6 个 Sheet（0 起）。 */
    private static final int TABLE2_SHEET = 5;

    private static MockMultipartFile workbook() throws IOException {
        Path p = Path.of(System.getProperty("user.dir"), WORKBOOK);
        assertTrue(Files.exists(p), "缺少测试工作簿，请先执行 _trash/gen_template.py 生成：" + p);
        return new MockMultipartFile("files", "multi_template_test.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                Files.readAllBytes(p));
    }

    @Test
    @DisplayName("表格2：能取到表头/列映射/数据行，且「场地」列不再算未识别")
    void table2GridHasHeadersRowsAndMapping() throws IOException {
        ExcelSheetInspector.SheetView view = ExcelSheetInspector.inspect(
                workbook(), "multi_template_test.xlsx", 0, TABLE2_SHEET, true, 1, 50, null, Map.of());

        assertEquals("event", view.type());
        // 前端靠 headers.size() 画列，为 0 会直接显示「没有可显示的数据行」
        assertEquals(19, view.headers().size(), "表头一个都不能少");
        assertEquals(2, view.totalRows(), "数据行数（不含表头行）");
        assertFalse(view.rows().isEmpty(), "第一页必须有行");
        // 网格里的列映射要和预览/导入用的是同一份：「场地号→场地编码」「场地→场地」
        assertEquals("defaultVenueCode", view.columnMap().get("8"));
        assertEquals("defaultVenue", view.columnMap().get("9"));
        List<String> unmapped = SheetPreviewBuilder.unmappedHeaders(view.headers(), view.columnMap(), view.type());
        assertTrue(unmapped.isEmpty(), "网格里不该出现未识别列：" + unmapped);
    }

    @Test
    @DisplayName("按人工指定的列映射取数：网格跟着用户改的映射走，没覆盖的列仍自动补齐")
    void gridFollowsManualColumnMap() throws IOException {
        Map<String, String> manual = new LinkedHashMap<>();
        manual.put("0", "maxParticipants"); // 第 0 列「代码」被管理员手动改挂到「最大报名人数」
        manual.put("1", "name");
        ExcelSheetInspector.SheetView view = ExcelSheetInspector.inspect(
                workbook(), "multi_template_test.xlsx", 0, TABLE2_SHEET, true, 1, 50, "event", manual);

        assertEquals("maxParticipants", view.columnMap().get("0"));
        assertEquals("name", view.columnMap().get("1"));
        // 契约：人工映射一旦非空就<b>完全接管</b>（effectiveColumnMap 的「人工优先」），
        // 没人工指定的列不再自动补齐 —— 所以网格里它们显示「未对上」，和真正导入时的口径一致。
        assertEquals(2, view.columnMap().size(), "人工改了 2 列，就只认这 2 列");
        assertEquals(null, view.columnMap().get("9"));
    }

    @Test
    @DisplayName("填写说明页：也能取到数据行（只是不参与导入），不能因为类型特殊就整页空白")
    void noticeSheetStillRenders() throws IOException {
        ExcelSheetInspector.SheetView view = ExcelSheetInspector.inspect(
                workbook(), "multi_template_test.xlsx", 0, 8, true, 1, 50, null, Map.of());

        assertEquals("notice", view.type());
        assertEquals(2, view.headers().size());
        assertFalse(view.rows().isEmpty(), "说明页的内容也要能看得到（管理员要照着它填表）");
        assertNotNull(view.sheetName());
    }
}
