package com.propertyops.pms.common.data;

import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/data")
public class DataResourceController {
    private final DataResourceService service;

    public DataResourceController(DataResourceService service) {
        this.service = service;
    }

    @GetMapping("/{resource}")
    PageResponse<Map<String, Object>> list(
            @PathVariable String resource,
            @RequestParam(required = false) String communityId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        return service.list(resource, communityId, keyword, status, category, page, size, sort);
    }

    @GetMapping("/{resource}/{id}")
    Map<String, Object> get(@PathVariable String resource, @PathVariable String id,
                            @RequestParam(required = false) String communityId) {
        return service.get(resource, id, communityId);
    }

    @PostMapping("/{resource}")
    Map<String, Object> create(@PathVariable String resource,
                               @RequestParam(required = false) String communityId,
                               @RequestBody Map<String, Object> body) {
        return service.create(resource, communityId, body);
    }

    @PutMapping("/{resource}/{id}")
    Map<String, Object> update(@PathVariable String resource, @PathVariable String id,
                               @RequestParam(required = false) String communityId,
                               @RequestParam long version,
                               @RequestBody Map<String, Object> body) {
        return service.update(resource, id, communityId, version, body);
    }

    @DeleteMapping("/{resource}/{id}")
    Map<String, Object> archive(@PathVariable String resource, @PathVariable String id,
                                @RequestParam(required = false) String communityId,
                                @RequestParam long version) {
        return service.archive(resource, id, communityId, version);
    }
}
