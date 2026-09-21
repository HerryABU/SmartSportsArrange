package com.sports.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文本编码自动识别测试：UTF-8(含BOM) / GB18030(GBK) / UTF-16LE。
 */
class FileEncodingTest {

    @Test
    void detectsUtf8WithoutBom() {
        byte[] raw = "年级,班级,姓名\n高一年级,高一1班,张三\n".getBytes(StandardCharsets.UTF_8);
        assertEquals(StandardCharsets.UTF_8, FileEncoding.detectCharset(raw));
        assertTrue(FileEncoding.decode(raw).contains("张三"));
    }

    @Test
    void stripsUtf8Bom() {
        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = "学号,姓名,性别\n2024001,李四,女".getBytes(StandardCharsets.UTF_8);
        byte[] raw = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, raw, 0, bom.length);
        System.arraycopy(body, 0, raw, bom.length, body.length);

        String text = FileEncoding.decode(raw);
        assertFalse(text.startsWith("\uFEFF"));
        assertTrue(text.contains("李四"));
    }

    @Test
    void detectsGb18030ChineseCsv() {
        Charset gbk = Charset.forName("GB18030");
        String sample = "年级,班级,姓名\n高一年级,高一1班,王五";
        byte[] raw = sample.getBytes(gbk);
        assertEquals(gbk, FileEncoding.detectCharset(raw));
        assertEquals(sample, FileEncoding.decode(raw));
    }

    @Test
    void detectsUtf16LeWithBom() {
        byte[] bom = new byte[]{(byte) 0xFF, (byte) 0xFE};
        String sample = "姓名,赵六";
        byte[] body = sample.getBytes(StandardCharsets.UTF_16LE);
        byte[] raw = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, raw, 0, bom.length);
        System.arraycopy(body, 0, raw, bom.length, body.length);
        assertTrue(FileEncoding.decode(raw).contains("赵六"));
    }
}
