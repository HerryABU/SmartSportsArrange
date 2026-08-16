package com.sports.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Page;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 统一 API 响应结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private int code;
    private String message;
    private T data;
    private Long timestamp;

    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
                .code(200)
                .message("success")
                .data(data)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder()
                .code(200)
                .message(message)
                .data(data)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return ApiResponse.<T>builder()
                .code(code)
                .message(message)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static <T> ApiResponse<T> error(int code, String message, T data) {
        return ApiResponse.<T>builder()
                .code(code)
                .message(message)
                .data(data)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    // 分页响应
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PageData<T> {
        private List<T> content;
        private int page;
        private int size;
        private long totalElements;
        private int totalPages;

        /** 兼容前端 records 别名 */
        public List<T> getRecords() { return content; }
        /** 兼容前端 total 别名 */
        public long getTotal() { return totalElements; }
        /** 兼容前端 list 别名 */
        public List<T> getList() { return content; }
    }

    public static <T> ApiResponse<PageData<T>> page(Page<T> page) {
        PageData<T> pageData = PageData.<T>builder()
                .content(page.getContent())
                .page(page.getNumber() + 1)
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .build();
        return ApiResponse.<PageData<T>>builder()
                .code(200)
                .message("success")
                .data(pageData)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
