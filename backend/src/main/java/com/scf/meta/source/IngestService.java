package com.scf.meta.source;

import com.scf.meta.catalog.CatalogEntry;
import com.scf.meta.catalog.CatalogEntryRepository;
import com.scf.meta.buildfact.IssuerService;
import com.scf.meta.common.HashUtil;
import com.scf.meta.identity.ComponentClaim;
import com.scf.meta.identity.ComponentClaimRepository;
import com.scf.meta.identity.IdentityResolutionService;
import com.scf.meta.review.ReviewService;
import com.scf.meta.signature.SignatureRecord;
import com.scf.meta.signature.SignatureRecordRepository;
import com.scf.meta.source.parser.PayloadParser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 来源保全册摄入服务：保存原始载荷、解析提取声明、触发身份归并。 */
@Service
public class IngestService {

    private final SourceArtifactRepository sourceRepo;
    private final ComponentClaimRepository claimRepo;
    private final SignatureRecordRepository signatureRepo;
    private final CatalogEntryRepository catalogRepo;
    private final PayloadParser parser;
    private final IdentityResolutionService identityService;
    private final IssuerService issuerService;
    private final ReviewService reviewService;
    private final String parserVersion;

    public IngestService(SourceArtifactRepository sourceRepo,
                         ComponentClaimRepository claimRepo,
                         SignatureRecordRepository signatureRepo,
                         CatalogEntryRepository catalogRepo,
                         PayloadParser parser,
                         IdentityResolutionService identityService,
                         IssuerService issuerService,
                         ReviewService reviewService,
                         @Value("${scf.parser-version}") String parserVersion) {
        this.sourceRepo = sourceRepo;
        this.claimRepo = claimRepo;
        this.signatureRepo = signatureRepo;
        this.catalogRepo = catalogRepo;
        this.parser = parser;
        this.identityService = identityService;
        this.issuerService = issuerService;
        this.reviewService = reviewService;
        this.parserVersion = parserVersion;
    }

    public record IngestCommand(String type, String sourceUri, String ecosystem, String buildId,
                                String payload, Instant eventTime, Instant collectedAt, String actor,
                                String revisesId, String originId, String completeness,
                                String completenessNote, Instant validFrom, Instant validTo) {
        /** 兼容旧调用：不含来源时间账扩展字段。 */
        public IngestCommand(String type, String sourceUri, String ecosystem, String buildId,
                             String payload, Instant eventTime, Instant collectedAt, String actor) {
            this(type, sourceUri, ecosystem, buildId, payload, eventTime, collectedAt, actor,
                    null, null, null, null, null, null);
        }
    }

    @Transactional
    public SourceArtifact ingest(IngestCommand cmd) {
        SourceArtifact artifact = new SourceArtifact();
        try {
            SourceArtifact.Type.valueOf(cmd.type());
        } catch (Exception e) {
            throw new IllegalArgumentException("未知来源类型: " + cmd.type());
        }
        if (cmd.payload() == null || cmd.payload().isBlank()) {
            throw new IllegalArgumentException("payload 不能为空");
        }
        artifact.type = cmd.type();
        artifact.sourceUri = cmd.sourceUri();
        artifact.ecosystem = cmd.ecosystem();
        artifact.buildId = cmd.buildId();
        artifact.payload = cmd.payload();
        artifact.payloadHash = HashUtil.sha256(cmd.payload());
        artifact.parserVersion = parserVersion;
        artifact.eventTime = cmd.eventTime();
        artifact.collectedAt = cmd.collectedAt() != null ? cmd.collectedAt() : Instant.now();
        artifact.ingestStatus = SourceArtifact.IngestStatus.RECEIVED.name();
        artifact.revisesId = cmd.revisesId();
        artifact.originId = cmd.originId();
        artifact.completeness = cmd.completeness();
        artifact.completenessNote = cmd.completenessNote();
        artifact.validFrom = cmd.validFrom();
        artifact.validTo = cmd.validTo();
        sourceRepo.save(artifact);

        List<String> warnings = new ArrayList<>();
        try {
            PayloadParser.ParseResult result = parser.parse(artifact);
            warnings.addAll(result.warnings);
            for (ComponentClaim c : result.claims) claimRepo.save(c);
            for (String[] sig : result.signatures) {
                SignatureRecord record = new SignatureRecord();
                record.sourceArtifactId = artifact.id;
                record.subjectHash = sig[0];
                record.signer = sig[1];
                if (sig.length > 6 && sig[6] != null) {
                    try {
                        record.validFrom = Instant.parse(sig[6]);
                    } catch (Exception ignored) {
                        // 非法生效时间格式时按自接收起有效
                    }
                }
                signatureRepo.save(record);
            }
            for (String[] entry : result.catalogEntries) upsertCatalog(entry);
            artifact.ingestStatus = warnings.isEmpty()
                    ? SourceArtifact.IngestStatus.PARSED.name()
                    : SourceArtifact.IngestStatus.PARSED_WITH_WARNINGS.name();
        } catch (Exception e) {
            artifact.ingestStatus = SourceArtifact.IngestStatus.REJECTED.name();
            artifact.note = "解析失败: " + e.getMessage();
            sourceRepo.save(artifact);
            reviewService.record("SOURCE", artifact.id, "INGEST_REJECTED", artifact.note, cmd.actor());
            return artifact;
        }
        if (!warnings.isEmpty()) {
            artifact.note = String.join("\n", warnings);
        }
        sourceRepo.save(artifact);
        reviewService.record("SOURCE", artifact.id, "INGESTED",
                artifact.type + " hash=" + artifact.payloadHash.substring(0, 12)
                        + " claims=" + claimRepo.findBySourceArtifactId(artifact.id).size(),
                cmd.actor());

        // 触发受影响声明组的身份归并
        List<ComponentClaim> claims = claimRepo.findBySourceArtifactId(artifact.id);
        identityService.resolveForClaims(claims, cmd.actor());
        // 若该构建已签发事实且此来源晚于截止点，登记为后续差异来源
        issuerService.registerLateSourceIfNeeded(artifact);
        return artifact;
    }

    private void upsertCatalog(String[] entry) {
        String ecosystem = entry[0] == null ? "unknown" : entry[0];
        String namespace = entry[1] == null ? "" : entry[1];
        CatalogEntry existing = catalogRepo
                .findByEcosystemAndNamespaceAndName(ecosystem, namespace, entry[2])
                .orElse(null);
        if (existing == null) {
            CatalogEntry e = new CatalogEntry();
            e.ecosystem = ecosystem;
            e.namespace = namespace;
            e.name = entry[2];
            e.publisher = entry[3];
            e.aliases = entry[4] == null ? "[]" : entry[4];
            catalogRepo.save(e);
        }
    }
}
