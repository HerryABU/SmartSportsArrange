package com.sports.controller.meet;

import com.sports.common.web.ApiResponse;
import com.sports.entity.meet.SportsMeet;
import com.sports.service.meet.MeetMaintenanceService;
import com.sports.service.meet.MeetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 届 / 运动会 管理接口。
 */
@Slf4j
@RestController
@RequestMapping("/api/meets")
@RequiredArgsConstructor
public class MeetController {

    private final MeetService meetService;
    private final MeetMaintenanceService meetMaintenanceService;

    @GetMapping
    public ApiResponse<List<SportsMeet>> list() {
        return ApiResponse.success(meetService.list());
    }

    @GetMapping("/active")
    public ApiResponse<SportsMeet> active() {
        return ApiResponse.success(meetService.getActive().orElse(null));
    }

    /** 当前届（兼容前端 app store 调用 /meets/current） */
    @GetMapping("/current")
    public ApiResponse<SportsMeet> current() {
        return ApiResponse.success(meetService.getActive().orElse(null));
    }

    @PostMapping
    public ApiResponse<SportsMeet> create(@RequestBody Map<String, Object> body) {
        return ApiResponse.success("创建成功", meetService.create(body));
    }

    @PutMapping("/{id}")
    public ApiResponse<SportsMeet> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.success("更新成功", meetService.update(id, body));
    }

    @PostMapping("/{id}/activate")
    public ApiResponse<SportsMeet> activate(@PathVariable Long id) {
        return ApiResponse.success("已设为当前届", meetService.setActive(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        meetService.delete(id);
        return ApiResponse.success("删除成功", null);
    }

    /** 按当前届年份重算毕业生标记（切换当前届后建议调用） */
    @PostMapping("/recompute-graduation")
    public ApiResponse<Map<String, Object>> recomputeGraduation() {
        int changed = meetMaintenanceService.recomputeGraduation();
        return ApiResponse.success("重算完成", Map.of("changed", changed));
    }
}
