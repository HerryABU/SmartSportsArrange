package com.sports.controller;

import com.sports.common.ApiResponse;
import com.sports.entity.Venue;
import com.sports.service.VenueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/venues")
@RequiredArgsConstructor
public class VenueController {

    private final VenueService venueService;

    @GetMapping
    public ApiResponse<List<Venue>> list() {
        return ApiResponse.success(venueService.listAll());
    }

    @GetMapping("/enabled")
    public ApiResponse<List<Venue>> listEnabled() {
        return ApiResponse.success(venueService.listEnabled());
    }

    @PostMapping
    public ApiResponse<Venue> create(@RequestBody Venue venue) {
        log.info("创建场地: code={}, name={}", venue.getCode(), venue.getName());
        return ApiResponse.success("创建成功", venueService.create(venue));
    }

    @PutMapping("/{id}")
    public ApiResponse<Venue> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        log.info("更新场地: id={}, 字段={}", id, body.keySet());
        return ApiResponse.success("更新成功", venueService.update(id, body));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        log.info("删除场地: id={}", id);
        venueService.delete(id);
        return ApiResponse.success("删除成功", null);
    }
}
