package com.sports.service.excel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可视化预览取数（{@link ExcelSheetInspector}）单测。
 *
 * <p>前端「可视化预览」按钮走的就是 {@code /excel/multi/sheet-data}，后端这套取数只要有一处
 * 吞掉异常（例如 Sheet 序号越界、表头行读不出来），用户看到的不是报错，而是一句
 * 「这张表暂时没有可显示的数据行」——功能等于不可用。这里用<b>真实的多表工作簿</b>
 * （9 个 Sheet，含「项目表（表格2）」这种 19 列表）把这条链路钉住。</p>
 *
 * <h2>⚠️ 夹具必须是「生产模板本身」</h2>
 *
 * <p>此前这个用例读的是 {@code ../_trash/multi_template_test.xlsx} —— 一个由某位开发者的
 * 私人脚本 {@code _trash/gen_template.py} 手工生成、并被 {@code .gitignore} 忽略的文件。
 * 两个后果都很实在：</p>
 *
 * <ol>
 *   <li><b>测试会因「清理工作区」整片变红</b>：清掉 {@code _trash/} 之后本类 3 个用例全挂
 *       （报「缺少测试工作簿」），而产品代码一行没改 —— 假失败；</li>
 *   <li><b>测不出真问题</b>：夹具的列序已经与产品模板漂移（夹具把「场地」放在第 9 列，
 *       而产品模板第 9 列是「性别」、第 13 列才是「场地」）。夹具和夹具自己的解析器当然一致，
 *       于是「用户下载到的模板能不能被正确解析」这件事<b>从来没被验证过</b>。</li>
 * </ol>
 *
 * <p>现在直接调用 {@link ExcelService#writeMultiWorkbook} 现场生成夹具 ——
 * 与用户点「下载模板」拿到的是同一份字节。模板列序一改，这里立刻跟着红。</p>
 */
@DisplayName("多表导入：可视化预览取数")
class ExcelSheetInspectorTest {

    /** 「项目表（表格2）」在多表工作簿里是第 6 个 Sheet（0 起）。 */
    private static final int TABLE2_SHEET = 5;

    /** 「填写说明」是最后一个 Sheet（0 起）。 */
    private static final int NOTICE_SHEET = 8;

    /** 现场生成夹具：直接调生产模板生成器，不再依赖任何磁盘上的手工文件。 */
    private static MockMultipartFile workbook() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ExcelService.writeMultiWorkbook(bos);
        assertTrue(bos.size() > 0, "多表模板生成器必须产出非空工作簿");
        return new MockMultipartFile("files", "multi_template_test.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                bos.toByteArray());
    }

    /** 表头里某列的 0 起下标（不写死数字：模板列序变了这里会跟着变，而不是静默错位）。 */
    private static String indexOf(List<String> headers, String header) {
        int i = headers.indexOf(header);
        assertTrue(i >= 0, "模板表头里找不到「" + header + "」：" + headers);
        return String.valueOf(i);
    }

    @Test
    @DisplayName("表格2：能取到表头/列映射/数据行，且每个表头都能对上字段（不出现未识别列）")
    void table2GridHasHeadersRowsAndMapping() throws IOException {
        ExcelSheetInspector.SheetView view = ExcelSheetInspector.inspect(
                workbook(), "multi_template_test.xlsx", 0, TABLE2_SHEET, true, 1, 50, null, Map.of());

        assertEquals("event", view.type());
        // 前端靠 headers.size() 画列，为 0 会直接显示「没有可显示的数据行」
        assertEquals(19, view.headers().size(), "表格2 必须 19 列，一个都不能少");
        assertTrue(view.totalRows() > 0, "模板自带示例行，应能读出数据行");
        assertFalse(view.rows().isEmpty(), "第一页必须有行");

        // 网格里的列映射要和预览/导入用的是同一份（别名口径见 ExcelColumnMapping）：
        // 「场地编码→defaultVenueCode」「场地→defaultVenue」。⚠️ 这里用**表头名**定位列，
        // 不写死列号 —— 夹具曾是手工文件、列序与产品模板漂移，写死列号会掩盖那个漂移。
        assertEquals("defaultVenueCode", view.columnMap().get(indexOf(view.headers(), "场地编码")));
        assertEquals("defaultVenue", view.columnMap().get(indexOf(view.headers(), "场地")));
        // 这两列曾经因为「包含匹配是双向的」被互相抢占（场地号 被 场地 抢走），
        // 所以「整表不得有未识别列」才是这条链路最关键的钉子。
        List<String> unmapped = SheetPreviewBuilder.unmappedHeaders(view.headers(), view.columnMap(), view.type());
        assertTrue(unmapped.isEmpty(), "网格里不该出现未识别列：" + unmapped);
    }

    @Test
    @DisplayName("按人工指定的列映射取数：网格跟着用户改的映射走，没覆盖的列不再自动补齐")
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
        String venueCodeIdx = indexOf(view.headers(), "场地编码");
        assertNull(view.columnMap().get(venueCodeIdx), "未在人工映射里的列不得被自动补齐");
    }

    @Test
    @DisplayName("填写说明页：也能取到数据行（只是不参与导入），不能因为类型特殊就整页空白")
    void noticeSheetStillRenders() throws IOException {
        ExcelSheetInspector.SheetView view = ExcelSheetInspector.inspect(
                workbook(), "multi_template_test.xlsx", 0, NOTICE_SHEET, true, 1, 50, null, Map.of());

        assertEquals("notice", view.type());
        assertEquals(2, view.headers().size());
        assertFalse(view.rows().isEmpty(), "说明页的内容也要能看得到（管理员要照着它填表）");
        assertNotNull(view.sheetName());
    }
}
