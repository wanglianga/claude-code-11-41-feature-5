package com.scf.meta.catalog;

import com.scf.meta.common.ApiResponse;
import com.scf.meta.common.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    private final CatalogService catalogService;
    private final CatalogEntryRepository entryRepo;
    private final CatalogRevisionRepository revisionRepo;
    private final ConsumerViewRepository viewRepo;

    public CatalogController(CatalogService catalogService, CatalogEntryRepository entryRepo,
                             CatalogRevisionRepository revisionRepo,
                             ConsumerViewRepository viewRepo) {
        this.catalogService = catalogService;
        this.entryRepo = entryRepo;
        this.revisionRepo = revisionRepo;
        this.viewRepo = viewRepo;
    }

    public record EntryRequest(String ecosystem, String namespace, @NotBlank String name,
                               String publisher, String aliases) {}

    @PostMapping("/entries")
    public ApiResponse<CatalogEntry> createEntry(@RequestBody EntryRequest req,
                                                 HttpServletRequest http) {
        return ApiResponse.ok(catalogService.createEntry(req.ecosystem(), req.namespace(),
                req.name(), req.publisher(), req.aliases(), actor(http)));
    }

    @GetMapping("/entries")
    public ApiResponse<List<CatalogEntry>> entries() {
        return ApiResponse.ok(entryRepo.findAll());
    }

    public record ReviseRequest(String publisher, String aliases, String summary, String effectiveFrom) {}

    @PostMapping("/entries/{id}/revise")
    public ApiResponse<CatalogRevision> revise(@PathVariable String id,
                                               @RequestBody ReviseRequest req,
                                               HttpServletRequest http) {
        java.time.Instant effectiveFrom = (req.effectiveFrom() == null || req.effectiveFrom().isBlank())
                ? null : java.time.Instant.parse(req.effectiveFrom());
        return ApiResponse.ok(catalogService.reviseEntry(id, req.publisher(), req.aliases(),
                req.summary(), effectiveFrom, actor(http)));
    }

    @GetMapping("/entries/{id}/revisions")
    public ApiResponse<List<CatalogRevision>> revisions(@PathVariable String id) {
        return ApiResponse.ok(revisionRepo.findByCatalogEntryIdOrderByRevisionDesc(id));
    }

    public record ViewRequest(@NotBlank String viewType, @NotBlank String name,
                              @NotBlank String buildFactId) {}

    @PostMapping("/views")
    public ApiResponse<ConsumerView> createView(@RequestBody ViewRequest req,
                                                HttpServletRequest http) {
        return ApiResponse.ok(catalogService.createView(req.viewType(), req.name(),
                req.buildFactId(), actor(http)));
    }

    @GetMapping("/views")
    public ApiResponse<List<ConsumerView>> views(@RequestParam(required = false) String status) {
        if (status != null && !status.isBlank()) {
            return ApiResponse.ok(viewRepo.findByStatus(status));
        }
        return ApiResponse.ok(viewRepo.findAll());
    }

    private static String actor(HttpServletRequest http) {
        return (String) http.getAttribute(AuthInterceptor.ATTR_USER);
    }
}
