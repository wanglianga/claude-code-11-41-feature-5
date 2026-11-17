package com.scf.meta.explain;

import com.scf.meta.common.ApiResponse;
import com.scf.meta.common.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 时点数据解释服务：复演任务、逐项解释、双时点对比与导出。 */
@RestController
@RequestMapping("/api/explain")
public class ExplainController {

    private final ReplayService replayService;
    private final ExplainService explainService;
    private final ReplayTaskRepository taskRepo;
    private final ReplayItemRepository itemRepo;

    public ExplainController(ReplayService replayService, ExplainService explainService,
                             ReplayTaskRepository taskRepo, ReplayItemRepository itemRepo) {
        this.replayService = replayService;
        this.explainService = explainService;
        this.taskRepo = taskRepo;
        this.itemRepo = itemRepo;
    }

    public record CoordQueryRequest(String ecosystem, String namespace, String name,
                                    String version) {}

    public record ReplayRequest(@NotBlank String buildId, @NotBlank String businessTime,
                                @NotBlank String knowledgeCutoff,
                                List<CoordQueryRequest> queries,
                                List<String> excludeScopes, List<String> requireFields) {}

    /** 创建并执行复演任务：固定业务时点、资料截止时间、规则与查询条件。 */
    @PostMapping("/replay")
    public ApiResponse<Map<String, Object>> replay(@RequestBody ReplayRequest req,
                                                   HttpServletRequest http) {
        String actor = (String) http.getAttribute(AuthInterceptor.ATTR_USER);
        ReplayService.ReplayQuery query = new ReplayService.ReplayQuery(
                req.queries() == null ? List.of()
                        : req.queries().stream()
                        .map(c -> new ReplayService.CoordQuery(c.ecosystem(), c.namespace(),
                                c.name(), c.version()))
                        .toList(),
                req.excludeScopes() == null ? List.of() : req.excludeScopes(),
                req.requireFields() == null ? List.of() : req.requireFields());
        ReplayTask task = replayService.replay(req.buildId(), Instant.parse(req.businessTime()),
                Instant.parse(req.knowledgeCutoff()), query, actor);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task", task);
        result.put("items", itemRepo.findByReplayTaskId(task.id));
        return ApiResponse.ok(result);
    }

    @GetMapping("/replays")
    public ApiResponse<List<ReplayTask>> list(@RequestParam(required = false) String buildId) {
        if (buildId != null && !buildId.isBlank()) {
            return ApiResponse.ok(taskRepo.findByBuildIdOrderByCreatedAtDesc(buildId));
        }
        return ApiResponse.ok(taskRepo.findAllByOrderByCreatedAtDesc());
    }

    @GetMapping("/replays/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String id) {
        ReplayTask task = taskRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("复演任务不存在: " + id));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task", task);
        result.put("items", itemRepo.findByReplayTaskId(id));
        return ApiResponse.ok(result);
    }

    /** 逐项解释：适用资料、关联路径、条件判断、未决原因与精简组合。 */
    @GetMapping("/items/{itemId}/explanation")
    public ApiResponse<Map<String, Object>> explain(
            @PathVariable String itemId,
            @RequestParam(required = false) Integer budget) {
        return ApiResponse.ok(explainService.explain(itemId, budget));
    }

    public record CompareRequest(@NotBlank String otherCutoff) {}

    /** 双时点对比：同一业务时点切换资料截止时间，并排说明哪些依据改变了答案。 */
    @PostMapping("/replays/{id}/compare")
    public ApiResponse<Map<String, Object>> compare(@PathVariable String id,
                                                    @RequestBody CompareRequest req,
                                                    HttpServletRequest http) {
        String actor = (String) http.getAttribute(AuthInterceptor.ATTR_USER);
        return ApiResponse.ok(explainService.compare(id, Instant.parse(req.otherCutoff()), actor));
    }

    /** 导出：内容包含相同输入，可重新计算。 */
    @GetMapping("/replays/{id}/export")
    public ApiResponse<Map<String, Object>> export(@PathVariable String id) {
        return ApiResponse.ok(explainService.export(id));
    }
}
