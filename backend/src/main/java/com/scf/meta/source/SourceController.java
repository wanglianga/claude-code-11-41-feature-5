package com.scf.meta.source;

import com.scf.meta.common.ApiResponse;
import com.scf.meta.common.AuthInterceptor;
import com.scf.meta.identity.ComponentClaim;
import com.scf.meta.identity.ComponentClaimRepository;
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

@RestController
@RequestMapping("/api/sources")
public class SourceController {

    private final SourceArtifactRepository sourceRepo;
    private final ComponentClaimRepository claimRepo;
    private final IngestService ingestService;

    public SourceController(SourceArtifactRepository sourceRepo,
                            ComponentClaimRepository claimRepo,
                            IngestService ingestService) {
        this.sourceRepo = sourceRepo;
        this.claimRepo = claimRepo;
        this.ingestService = ingestService;
    }

    public record IngestRequest(@NotBlank String type, String sourceUri, String ecosystem,
                                String buildId, @NotBlank String payload,
                                String eventTime, String collectedAt,
                                String revisesId, String originId, String completeness,
                                String completenessNote, String validFrom, String validTo) {}

    @PostMapping
    public ApiResponse<SourceArtifact> ingest(@RequestBody IngestRequest req,
                                              HttpServletRequest http) {
        String actor = (String) http.getAttribute(AuthInterceptor.ATTR_USER);
        SourceArtifact artifact = ingestService.ingest(new IngestService.IngestCommand(
                req.type(), req.sourceUri(), req.ecosystem(), req.buildId(), req.payload(),
                parseInstant(req.eventTime()), parseInstant(req.collectedAt()), actor,
                req.revisesId(), req.originId(), req.completeness(), req.completenessNote(),
                parseInstant(req.validFrom()), parseInstant(req.validTo())));
        return ApiResponse.ok(artifact);
    }

    @GetMapping
    public ApiResponse<List<SourceArtifact>> list(@RequestParam(required = false) String type,
                                                  @RequestParam(required = false) String buildId) {
        if (buildId != null && !buildId.isBlank()) {
            return ApiResponse.ok(sourceRepo.findByBuildIdOrderByCollectedAtAsc(buildId));
        }
        if (type != null && !type.isBlank()) {
            return ApiResponse.ok(sourceRepo.findByTypeOrderByCollectedAtDesc(type));
        }
        return ApiResponse.ok(sourceRepo.findAllByOrderByCollectedAtDesc());
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String id) {
        SourceArtifact artifact = sourceRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("来源不存在: " + id));
        List<ComponentClaim> claims = claimRepo.findBySourceArtifactId(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("artifact", artifact);
        result.put("claims", claims);
        return ApiResponse.ok(result);
    }

    static Instant parseInstant(String s) {
        return (s == null || s.isBlank()) ? null : Instant.parse(s);
    }
}
