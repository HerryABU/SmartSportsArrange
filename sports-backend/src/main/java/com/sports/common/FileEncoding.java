package com.sports.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 文本文件编码自动识别（面向中文字符集）。
 *
 * <p>策略：优先识别 BOM（UTF-8 / UTF-16 LE/BE / UTF-32），
 * 无 BOM 时用「严格 UTF-8 解码」试探——若能无损解码则视为 UTF-8，
 * 否则按 GB18030（GBK 超集，Excel 另存 CSV 常见）解码；
 * GB18030 极难解码失败，若万一失败则回退 ISO-8859-1，保证不抛异常。</p>
 */
public final class FileEncoding {

    private static final Charset UTF8 = StandardCharsets.UTF_8;
    private static final Charset GB18030 = Charset.forName("GB18030");

    private FileEncoding() {
    }

    /**
     * 读取输入流并返回按自动识别编码解码后的文本（会剥离 BOM）。
     */
    public static String decode(InputStream in) throws IOException {
        return decode(in.readAllBytes());
    }

    /**
     * 将原始字节按自动识别编码解码为文本（会剥离 BOM）。
     */
    public static String decode(byte[] data) {
        if (data == null) return "";
        Charset charset = detectCharset(data);
        int offset = bomLength(data);
        int len = data.length - offset;
        if (len <= 0) return "";
        // UTF-16 的字节序与 BOM 一致时直接解码；此处 detect 已返回带 BOM 的 decoder 能处理的 charset
        try {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .decode(ByteBuffer.wrap(data, offset, len))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(data, offset, len, StandardCharsets.UTF_8);
        }
    }

    /**
     * 自动识别文本编码（按头部 BOM / UTF-8 无损试探 / GB18030 兜底）。
     */
    public static Charset detectCharset(byte[] data) {
        if (data == null || data.length == 0) return UTF8;
        if (startsWith(data, new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF})) return UTF8;
        if (startsWith(data, new byte[]{(byte) 0xFF, (byte) 0xFE, 0x00, 0x00})) return Charset.forName("UTF-32LE");
        if (startsWith(data, new byte[]{0x00, 0x00, (byte) 0xFE, (byte) 0xFF})) return Charset.forName("UTF-32BE");
        if (startsWith(data, new byte[]{(byte) 0xFF, (byte) 0xFE})) return StandardCharsets.UTF_16LE;
        if (startsWith(data, new byte[]{(byte) 0xFE, (byte) 0xFF})) return StandardCharsets.UTF_16BE;
        // 无 BOM：严格 UTF-8 试探
        try {
            Charset utf8 = UTF8;
            utf8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data));
            return utf8;
        } catch (CharacterCodingException e) {
            // 常见中文 CSV 来自 Excel「另存为 CSV(逗号分隔)」→ ANSI(GBK/GB18030)
            try {
                return GB18030;
            } catch (Exception ignored) {
                return StandardCharsets.ISO_8859_1;
            }
        }
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) return false;
        }
        return true;
    }

    /** BOM 字节数（无 BOM 返回 0） */
    private static int bomLength(byte[] data) {
        if (data == null || data.length < 2) return 0;
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            return 3; // UTF-8 BOM
        }
        if (data.length >= 4 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE && data[2] == 0 && data[3] == 0) {
            return 4; // UTF-32LE
        }
        if (data.length >= 4 && data[0] == 0 && data[1] == 0 && (data[2] & 0xFF) == 0xFE && (data[3] & 0xFF) == 0xFF) {
            return 4; // UTF-32BE
        }
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) return 2; // UTF-16LE
        if ((data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) return 2; // UTF-16BE
        return 0;
    }
}
