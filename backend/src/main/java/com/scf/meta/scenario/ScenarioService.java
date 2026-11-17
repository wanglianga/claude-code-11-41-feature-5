package com.scf.meta.scenario;

import com.scf.meta.buildfact.BuildFact;
import com.scf.meta.buildfact.BuildFactItem;
import com.scf.meta.buildfact.BuildFactItemRepository;
import com.scf.meta.buildfact.BuildFactRepository;
import com.scf.meta.buildfact.IssuerService;
import com.scf.meta.catalog.CatalogEntry;
import com.scf.meta.catalog.CatalogEntryRepository;
import com.scf.meta.catalog.CatalogService;
import com.scf.meta.catalog.ConsumerView;
import com.scf.meta.catalog.ConsumerViewRepository;
import com.scf.meta.common.Json;
import com.scf.meta.dependency.CompilerService;
import com.scf.meta.dependency.DependencyEdge;
import com.scf.meta.dependency.DependencyEdgeRepository;
import com.scf.meta.explain.ExplainService;
import com.scf.meta.explain.ReplayItem;
import com.scf.meta.explain.ReplayItemRepository;
import com.scf.meta.explain.ReplayService;
import com.scf.meta.explain.ReplayTask;
import com.scf.meta.explain.ReplayTaskRepository;
import com.scf.meta.identity.ComponentIdentity;
import com.scf.meta.identity.ComponentIdentityRepository;
import com.scf.meta.signature.SignatureRecord;
import com.scf.meta.signature.SignatureRecordRepository;
import com.scf.meta.signature.SignatureService;
import com.scf.meta.source.IngestService;
import com.scf.meta.source.SourceArtifact;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 验收场景编排：同名异发布者、哈希冲突、私有镜像、构建后补传、
 * 目录别名修订、签名撤销。每个场景使用独立构建标识，可重复运行。
 */
@Service
public class ScenarioService {

    private final IngestService ingestService;
    private final CompilerService compilerService;
    private final IssuerService issuerService;
    private final CatalogService catalogService;
    private final SignatureService signatureService;
    private final ComponentIdentityRepository identityRepo;
    private final DependencyEdgeRepository edgeRepo;
    private final BuildFactRepository factRepo;
    private final BuildFactItemRepository itemRepo;
    private final ConsumerViewRepository viewRepo;
    private final CatalogEntryRepository entryRepo;
    private final SignatureRecordRepository signatureRepo;
    private final ReplayService replayService;
    private final ExplainService explainService;
    private final ReplayTaskRepository replayTaskRepo;
    private final ReplayItemRepository replayItemRepo;
    private final Json json;

    public ScenarioService(IngestService ingestService, CompilerService compilerService,
                           IssuerService issuerService, CatalogService catalogService,
                           SignatureService signatureService,
                           ComponentIdentityRepository identityRepo,
                           DependencyEdgeRepository edgeRepo,
                           BuildFactRepository factRepo,
                           BuildFactItemRepository itemRepo,
                           ConsumerViewRepository viewRepo,
                           CatalogEntryRepository entryRepo,
                           SignatureRecordRepository signatureRepo,
                           ReplayService replayService, ExplainService explainService,
                           ReplayTaskRepository replayTaskRepo,
                           ReplayItemRepository replayItemRepo,
                           Json json) {
        this.ingestService = ingestService;
        this.compilerService = compilerService;
        this.issuerService = issuerService;
        this.catalogService = catalogService;
        this.signatureService = signatureService;
        this.identityRepo = identityRepo;
        this.edgeRepo = edgeRepo;
        this.factRepo = factRepo;
        this.itemRepo = itemRepo;
        this.viewRepo = viewRepo;
        this.entryRepo = entryRepo;
        this.signatureRepo = signatureRepo;
        this.replayService = replayService;
        this.explainService = explainService;
        this.replayTaskRepo = replayTaskRepo;
        this.replayItemRepo = replayItemRepo;
        this.json = json;
    }

    public static class ScenarioResult {
        public String scenario;
        public String buildId;
        public List<String> steps = new ArrayList<>();
        public List<Map<String, Object>> checks = new ArrayList<>();
        public Map<String, Object> objects = new LinkedHashMap<>();

        void check(String name, boolean passed, String detail) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("name", name);
            c.put("passed", passed);
            c.put("detail", detail);
            checks.add(c);
        }
    }

    public ScenarioResult run(String name) {
        return switch (name) {
            case "same-name-diff-publisher" -> sameNameDiffPublisher();
            case "hash-conflict" -> hashConflict();
            case "private-mirror" -> privateMirror();
            case "late-lockfile" -> lateLockfile();
            case "catalog-alias-revision" -> catalogAliasRevision();
            case "signature-revocation" -> signatureRevocation();
            case "replay-late-source" -> replayLateSource();
            case "replay-revision" -> replayRevision();
            case "replay-validity-copies" -> replayValidityCopies();
            case "replay-exclusive-absence" -> replayExclusiveAbsence();
            case "replay-minimal-evidence" -> replayMinimalEvidence();
            default -> throw new IllegalArgumentException("未知场景: " + name);
        };
    }

    private String newBuildId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private SourceArtifact ingest(String type, String eco, String buildId, String payload,
                                  Instant collectedAt, String uri) {
        return ingestService.ingest(new IngestService.IngestCommand(
                type, uri, eco, buildId, payload,
                collectedAt, collectedAt, "scenario"));
    }

    /** 带来源时间账字段的摄入：修订链、转抄来源、清单完整范围、业务有效区间。 */
    private SourceArtifact ingestFull(String type, String eco, String buildId, String payload,
                                      Instant collectedAt, String uri, String revisesId,
                                      String originId, String completeness, String completenessNote,
                                      Instant validFrom, Instant validTo) {
        return ingestService.ingest(new IngestService.IngestCommand(
                type, uri, eco, buildId, payload, collectedAt, collectedAt, "scenario",
                revisesId, originId, completeness, completenessNote, validFrom, validTo));
    }

    /** 场景1：同名包在 npm 与内部包仓发布者/哈希不同且缺少生态上下文 → 多个候选并存。 */
    private ScenarioResult sameNameDiffPublisher() {
        ScenarioResult r = new ScenarioResult();
        r.scenario = "same-name-diff-publisher";
        String buildId = newBuildId("sndp");
        r.buildId = buildId;

        SourceArtifact npm = ingest("REPO_METADATA", "npm", buildId,
                "{\"packages\":[{\"name\":\"shared-lib\",\"version\":\"1.0.0\","
                        + "\"publisher\":\"npmjs-official\",\"ecosystem\":\"npm\","
                        + "\"dist\":{\"integrity\":\"sha256-aaaa1111\"}}]}",
                Instant.now(), "https://registry.npmjs.org/shared-lib");
        r.steps.add("摄入 npm 包仓元数据: shared-lib@1.0.0 publisher=npmjs-official");

        SourceArtifact mirror = ingest("REPO_METADATA", null, buildId,
                "{\"mirror\":\"internal\",\"packages\":[{\"name\":\"shared-lib\",\"version\":\"1.0.0\","
                        + "\"publisher\":\"internal-platform-team\","
                        + "\"dist\":{\"shasum\":\"bbbb2222\"}}]}",
                Instant.now(), "https://mirror.internal.corp/repository/npm/shared-lib");
        r.steps.add("摄入内部镜像包仓元数据（缺生态上下文）: shared-lib@1.0.0 publisher=internal-platform-team");

        List<ComponentIdentity> identities = identityRepo.findByName("shared-lib").stream()
                .filter(i -> i.identityKey.contains("1.0.0")).toList();
        long candidates = identities.stream()
                .filter(i -> "CANDIDATE".equals(i.status)).count();
        r.check("保留多个候选身份", identities.size() >= 2 && candidates >= 2,
                "身份数=" + identities.size() + " 候选数=" + candidates);
        r.check("候选未被合并为已确认身份",
                identities.stream().noneMatch(i -> "CONFIRMED".equals(i.status)),
                "无 CONFIRMED 状态身份");
        r.check("私有镜像来源留下待核项", mirror.note != null && mirror.note.contains("待核"),
                "note=" + mirror.note);
        r.objects.put("npmSourceId", npm.id);
        r.objects.put("mirrorSourceId", mirror.id);
        r.objects.put("identityIds", identities.stream().map(i -> i.id).toList());
        return r;
    }

    /** 场景2：SBOM 与锁文件坐标相同哈希不同，构建日志不能证明任一哈希 → 来源冲突，不签发已证实依赖。 */
    private ScenarioResult hashConflict() {
        ScenarioResult r = new ScenarioResult();
        r.scenario = "hash-conflict";
        String buildId = newBuildId("hashconflict");
        r.buildId = buildId;

        ingest("SBOM", "npm", buildId,
                "{\"components\":[{\"name\":\"crypto-util\",\"version\":\"2.0.0\","
                        + "\"publisher\":\"upstream\",\"ecosystem\":\"npm\","
                        + "\"hashes\":[{\"alg\":\"SHA-256\",\"content\":\"hashA001\"}]}]}",
                Instant.now(), "https://ci.corp/jobs/" + buildId + "/sbom.json");
        r.steps.add("摄入 SBOM: crypto-util@2.0.0 sha256=hashA001");

        ingest("LOCKFILE", "npm", buildId,
                "{\"dependencies\":[{\"name\":\"crypto-util\",\"version\":\"2.0.0\","
                        + "\"publisher\":\"upstream\",\"ecosystem\":\"npm\","
                        + "\"integrity\":\"sha256-hashB002\"}]}",
                Instant.now(), "https://ci.corp/jobs/" + buildId + "/package-lock.json");
        r.steps.add("摄入锁文件: crypto-util@2.0.0 sha256=hashB002（坐标相同、哈希不同）");

        ingest("BUILD_LOG", "npm", buildId,
                "Resolved npm:crypto-util@2.0.0\nBuild completed in 42s",
                Instant.now(), "https://ci.corp/jobs/" + buildId + "/build.log");
        r.steps.add("摄入构建日志: 仅显示解析了坐标，未记录任一哈希");

        List<ComponentIdentity> identities = identityRepo.findByName("crypto-util").stream()
                .filter(i -> i.identityKey.contains("2.0.0")).toList();
        boolean conflict = identities.stream().anyMatch(i -> "CONFLICT".equals(i.status));
        r.check("身份工作区标为来源冲突", conflict,
                "状态=" + identities.stream().map(i -> i.status).distinct().toList());

        CompilerService.CompileResult compiled = compilerService.compile(buildId, null, "scenario");
        boolean noProven = compiled.edges().stream()
                .noneMatch(e -> "PROVEN".equals(e.edgeStatus));
        boolean hasRejected = compiled.edges().stream()
                .anyMatch(e -> "REJECTED".equals(e.edgeStatus));
        r.check("编译器不签发已证实依赖", noProven && hasRejected,
                "边状态=" + compiled.edges().stream().map(e -> e.edgeStatus).toList());

        BuildFact fact = issuerService.issue(buildId, null, "scenario");
        List<BuildFactItem> items = itemRepo.findByBuildFactId(fact.id);
        boolean factNoProven = items.stream()
                .filter(i -> "EDGE".equals(i.itemKind))
                .noneMatch(i -> json.read(i.snapshot).get("edgeStatus").asText().equals("PROVEN"));
        r.check("签发事实中无已证实依赖边", factNoProven, "factId=" + fact.id);
        r.objects.put("factId", fact.id);
        r.objects.put("identityIds", identities.stream().map(i -> i.id).toList());
        return r;
    }

    /** 场景3：私有镜像字段变体 → 单独映射、留下待核项、保持候选。 */
    private ScenarioResult privateMirror() {
        ScenarioResult r = new ScenarioResult();
        r.scenario = "private-mirror";
        String buildId = newBuildId("mirror");
        r.buildId = buildId;

        SourceArtifact mirror = ingest("REPO_METADATA", null, buildId,
                "{\"mirror\":\"nexus-internal\",\"packages\":[{\"name\":\"legacy-sdk\","
                        + "\"version\":\"0.9.1\",\"owner\":\"infra-group\","
                        + "\"dist\":{\"shasum\":\"cccc3333\"}}]}",
                Instant.now(), "https://mirror.internal.corp/repository/npm/legacy-sdk");
        r.steps.add("摄入私有镜像元数据: 字段为 owner/dist.shasum 变体且缺生态上下文");

        boolean warnings = "PARSED_WITH_WARNINGS".equals(mirror.ingestStatus);
        r.check("私有镜像字段变化被单独映射并记录待核项",
                warnings && mirror.note != null && mirror.note.contains("待核"),
                "ingestStatus=" + mirror.ingestStatus + " note=" + mirror.note);

        List<ComponentIdentity> identities = identityRepo.findByName("legacy-sdk");
        boolean candidate = identities.stream().allMatch(i -> "CANDIDATE".equals(i.status))
                && !identities.isEmpty();
        r.check("镜像声明保持候选不误合并", candidate,
                "状态=" + identities.stream().map(i -> i.status).toList());
        r.objects.put("mirrorSourceId", mirror.id);
        r.objects.put("identityIds", identities.stream().map(i -> i.id).toList());
        return r;
    }

    /** 场景4：构建日志显示解析完成后锁文件补传且版本不同 → 原事实按截止点证据签发，补传件登记为后续差异。 */
    private ScenarioResult lateLockfile() {
        ScenarioResult r = new ScenarioResult();
        r.scenario = "late-lockfile";
        String buildId = newBuildId("late");
        r.buildId = buildId;

        Instant t0 = Instant.now().minus(2, ChronoUnit.HOURS);
        ingest("BUILD_LOG", "maven", buildId,
                "Resolved maven:com.acme/order-service@1.2.3 sha256=dddd4444 publisher=acme-release\n"
                        + "Resolution completed.",
                t0, "https://ci.corp/jobs/" + buildId + "/build.log");
        r.steps.add("T0 摄入构建日志: 解析完成 order-service@1.2.3 sha256=dddd4444");

        Instant cutoff = t0.plus(30, ChronoUnit.MINUTES);
        BuildFact fact = issuerService.issue(buildId, cutoff, "scenario");
        r.steps.add("T0+30m 以截止点签发构建事实 v" + fact.factVersion);

        Instant t2 = cutoff.plus(1, ChronoUnit.HOURS);
        SourceArtifact lateLock = ingest("LOCKFILE", "maven", buildId,
                "{\"dependencies\":[{\"name\":\"order-service\",\"version\":\"1.2.4\","
                        + "\"namespace\":\"com.acme\",\"ecosystem\":\"maven\","
                        + "\"integrity\":\"sha256-eeee5555\"}]}",
                t2, "https://ci.corp/jobs/" + buildId + "/late-lock.json");
        r.steps.add("T0+90m 锁文件补传: order-service@1.2.4（版本不同）");

        BuildFact reloaded = factRepo.findById(fact.id).orElseThrow();
        List<BuildFactItem> items = itemRepo.findByBuildFactId(fact.id);
        boolean proven123 = items.stream()
                .filter(i -> "EDGE".equals(i.itemKind))
                .anyMatch(i -> {
                    var n = json.read(i.snapshot);
                    return "PROVEN".equals(n.get("edgeStatus").asText())
                            && n.get("toLabel").asText().contains("1.2.3");
                });
        r.check("原事实以截止点可见证据签发（1.2.3 已证实）", proven123,
                "事实边快照含 1.2.3 PROVEN=" + proven123);

        boolean lateRegistered = reloaded.postCutoffSources != null
                && reloaded.postCutoffSources.contains(lateLock.id);
        r.check("补传锁文件登记为后续差异来源", lateRegistered,
                "postCutoffSources=" + reloaded.postCutoffSources);

        boolean no124 = items.stream()
                .filter(i -> "EDGE".equals(i.itemKind))
                .noneMatch(i -> json.read(i.snapshot).get("toLabel").asText().contains("1.2.4"));
        r.check("原事实未被补传件改写（无 1.2.4 边）", no124, "事实条目数=" + items.size());
        r.objects.put("factId", fact.id);
        r.objects.put("lateLockfileSourceId", lateLock.id);
        return r;
    }

    /** 场景5：目录别名/发布者修订 → 监测器定位当前引用并暂停下游视图，历史保留当时身份版本。 */
    private ScenarioResult catalogAliasRevision() {
        ScenarioResult r = new ScenarioResult();
        r.scenario = "catalog-alias-revision";
        String buildId = newBuildId("catalog");
        r.buildId = buildId;

        ingest("SBOM", "npm", buildId,
                "{\"components\":[{\"name\":\"web-framework\",\"version\":\"5.1.0\","
                        + "\"publisher\":\"old-alias-team\",\"ecosystem\":\"npm\","
                        + "\"hashes\":[{\"alg\":\"SHA-256\",\"content\":\"ffff6666\"}]}]}",
                Instant.now(), "https://ci.corp/jobs/" + buildId + "/sbom.json");
        r.steps.add("摄入 SBOM: web-framework@5.1.0 publisher=old-alias-team");

        CatalogEntry entry = catalogService.createEntry("npm", "", "web-framework",
                "old-alias-team", "[\"web-fw\"]", "scenario");
        r.steps.add("登记组件目录条目: web-framework 别名 [web-fw]");

        BuildFact fact = issuerService.issue(buildId, null, "scenario");
        ConsumerView view = catalogService.createView("RISK_VIEW", "风险视图-" + buildId,
                fact.id, "scenario");
        r.steps.add("签发事实 v" + fact.factVersion + " 并创建下游风险视图");

        catalogService.reviseEntry(entry.id, "new-platform-org",
                "[\"web-fw\", \"web-framework-next\"]", "发布者归属变更", "scenario");
        r.steps.add("目录修订: 发布者 old-alias-team → new-platform-org，新增别名");

        ConsumerView reloaded = viewRepo.findById(view.id).orElseThrow();
        r.check("变动监测器暂停当前下游视图", "SUSPENDED".equals(reloaded.status),
                "视图状态=" + reloaded.status + " 原因=" + reloaded.suspendReason);
        boolean snapshotKept = reloaded.identityVersions != null
                && reloaded.identityVersions.contains("\"");
        r.check("历史视图保留当时身份版本快照", snapshotKept,
                "identityVersions=" + reloaded.identityVersions);
        CatalogEntry updated = entryRepo.findById(entry.id).orElseThrow();
        r.check("目录修订记录版本递增", updated.revision == 2, "revision=" + updated.revision);
        r.objects.put("factId", fact.id);
        r.objects.put("viewId", view.id);
        r.objects.put("catalogEntryId", entry.id);
        return r;
    }

    /** 场景6：作为唯一依据的签名撤销 → 身份降为证据不足，新事实不能继续显示已证实。 */
    private ScenarioResult signatureRevocation() {
        ScenarioResult r = new ScenarioResult();
        r.scenario = "signature-revocation";
        String buildId = newBuildId("sigrevoke");
        r.buildId = buildId;
        // 每次运行使用唯一主体哈希，避免与历史运行的签名记录冲突
        String subjectHash = "sig" + UUID.randomUUID().toString().substring(0, 8);

        ingest("SIGNATURE_INDEX", "npm", buildId,
                "{\"signatures\":[{\"subjectHash\":\"" + subjectHash + "\",\"signer\":\"release-key-1\","
                        + "\"component\":{\"name\":\"secure-bundle\",\"version\":\"3.0.0\","
                        + "\"ecosystem\":\"npm\",\"publisher\":\"security-team\"}}]}",
                Instant.now(), "https://sigstore.corp/index/secure-bundle");
        r.steps.add("摄入签名索引: secure-bundle@3.0.0 唯一依据为签名 " + subjectHash);

        ComponentIdentity identity = identityRepo.findByName("secure-bundle").stream()
                .filter(i -> i.identityKey.contains(subjectHash)).findFirst().orElseThrow();
        r.check("签名有效时身份已确认", "CONFIRMED".equals(identity.status),
                "状态=" + identity.status);

        BuildFact fact1 = issuerService.issue(buildId, null, "scenario");
        boolean provenBefore = itemRepo.findByBuildFactId(fact1.id).stream()
                .filter(i -> "EDGE".equals(i.itemKind))
                .anyMatch(i -> "PROVEN".equals(json.read(i.snapshot).get("edgeStatus").asText()));
        r.check("撤销前事实含已证实依赖边", provenBefore, "fact v" + fact1.factVersion);

        SignatureRecord sig = signatureRepo.findBySubjectHash(subjectHash).stream()
                .filter(s -> "VALID".equals(s.status)).findFirst().orElseThrow();
        signatureService.revoke(sig.id, "scenario");
        r.steps.add("撤销签名 " + subjectHash);

        ComponentIdentity downgraded = identityRepo.findById(identity.id).orElseThrow();
        r.check("受影响组件降为证据不足", "INSUFFICIENT_EVIDENCE".equals(downgraded.status),
                "状态=" + downgraded.status + " versionNo=" + downgraded.versionNo);

        BuildFact fact2 = issuerService.issue(buildId, null, "scenario");
        boolean noProvenAfter = itemRepo.findByBuildFactId(fact2.id).stream()
                .filter(i -> "EDGE".equals(i.itemKind))
                .noneMatch(i -> "PROVEN".equals(json.read(i.snapshot).get("edgeStatus").asText()));
        r.check("撤销后新事实不能继续显示已证实", noProvenAfter,
                "fact v" + fact2.factVersion + " 无 PROVEN 边");
        r.objects.put("factIdBefore", fact1.id);
        r.objects.put("factIdAfter", fact2.id);
        r.objects.put("identityId", identity.id);
        r.objects.put("signatureId", sig.id);
        return r;
    }

    // ------------------------------------------------------------------
    // 时点复演与解释场景
    // ------------------------------------------------------------------

    private ReplayItem findItem(String taskId, String nameContains) {
        return replayItemRepo.findByReplayTaskId(taskId).stream()
                .filter(i -> i.coordinate.contains(nameContains))
                .findFirst().orElse(null);
    }

    private String itemsSignature(String taskId) {
        return replayItemRepo.findByReplayTaskId(taskId).stream()
                .map(i -> i.coordKey + "|" + i.itemType + "|" + i.reasonCode)
                .sorted().reduce((a, b) -> a + ";" + b).orElse("");
    }

    /** 场景7：晚到资料 —— 同一业务时点两个资料截止时间结论不同，变化归因到补传锁文件。 */
    private ScenarioResult replayLateSource() {
        ScenarioResult r = new ScenarioResult();
        r.scenario = "replay-late-source";
        String buildId = newBuildId("late-src");
        r.buildId = buildId;
        Instant t0 = Instant.now().minus(3, ChronoUnit.DAYS);

        ingestFull("SBOM", "npm", buildId,
                "{\"components\":[{\"name\":\"alpha\",\"version\":\"1.0.0\","
                        + "\"publisher\":\"core-team\",\"ecosystem\":\"npm\","
                        + "\"hashes\":[{\"alg\":\"SHA-256\",\"content\":\"a1b2c3\"}]}]}",
                t0, "https://ci.corp/jobs/" + buildId + "/sbom.json",
                null, null, "COMPLETE", "覆盖 runtime 全部直接依赖", null, null);
        r.steps.add("T0 摄入完整 SBOM：alpha@1.0.0（覆盖 runtime 全部直接依赖）");

        Instant t2 = t0.plus(2, ChronoUnit.DAYS);
        SourceArtifact lock = ingestFull("LOCKFILE", "npm", buildId,
                "{\"dependencies\":[{\"name\":\"alpha\",\"version\":\"1.0.0\","
                        + "\"publisher\":\"core-team\",\"ecosystem\":\"npm\",\"integrity\":\"sha256-a1b2c3\"},"
                        + "{\"name\":\"late-lib\",\"version\":\"2.0.0\",\"publisher\":\"core-team\","
                        + "\"ecosystem\":\"npm\",\"integrity\":\"sha256-l4t3lib\"}]}",
                t2, "https://ci.corp/jobs/" + buildId + "/package-lock.json",
                null, null, null, null, null, null);
        r.steps.add("T0+2d 补传锁文件（描述同一较早构建）：alpha + late-lib@2.0.0");

        Instant b = t0.plus(6, ChronoUnit.HOURS);
        ReplayTask task1 = replayService.replay(buildId, b, t0.plus(12, ChronoUnit.HOURS),
                new ReplayService.ReplayQuery(
                        List.of(new ReplayService.CoordQuery("npm", null, "late-lib", "2.0.0")),
                        List.of(), List.of()), "scenario");
        r.steps.add("复演一：业务时点 T0+6h，资料截止 T0+12h");

        ReplayItem alpha1 = findItem(task1.id, "alpha");
        r.check("复演一 alpha 已证实出现", alpha1 != null && "PRESENT".equals(alpha1.itemType),
                "alpha=" + (alpha1 == null ? "无" : alpha1.itemType));
        ReplayItem late1 = findItem(task1.id, "late-lib");
        r.check("复演一 late-lib 未知（来源未收到）",
                late1 != null && "UNKNOWN".equals(late1.itemType)
                        && "SOURCE_NOT_RECEIVED".equals(late1.reasonCode),
                "late-lib=" + (late1 == null ? "无" : late1.itemType + "/" + late1.reasonCode));

        Map<String, Object> cmp = explainService.compare(task1.id,
                Instant.now().plus(1, ChronoUnit.DAYS), "scenario");
        r.steps.add("对比：同一业务时点，资料截止后移到当前");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changes = (List<Map<String, Object>>) cmp.get("changes");
        Map<String, Object> lateChange = changes.stream()
                .filter(c -> c.get("coordinate").toString().contains("late-lib"))
                .findFirst().orElse(null);
        r.check("同一业务时点不同资料截止得到不同结论",
                lateChange != null && lateChange.get("before").toString().contains("SOURCE_NOT_RECEIVED")
                        && lateChange.get("after").toString().contains("PRESENT"),
                "change=" + lateChange);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attributions = lateChange == null ? List.of()
                : (List<Map<String, Object>>) lateChange.get("attributions");
        r.check("变化归因到补传锁文件",
                attributions != null && attributions.stream()
                        .anyMatch(a -> lock.id.equals(a.get("id"))),
                "attributions=" + attributions);

        ReplayItem late1After = findItem(task1.id, "late-lib");
        r.check("原先复演结果保持可回查（不被改写）",
                late1After != null && "SOURCE_NOT_RECEIVED".equals(late1After.reasonCode),
                "复演一 late-lib 仍为 " + (late1After == null ? "无" : late1After.reasonCode));

        // 导出内容可用相同输入重新计算
        Map<String, Object> export = explainService.export(task1.id);
        @SuppressWarnings("unchecked")
        Map<String, Object> inputs = (Map<String, Object>) export.get("inputs");
        ReplayTask recomputed = replayService.replay(buildId,
                Instant.parse(inputs.get("businessTime").toString()),
                Instant.parse(inputs.get("knowledgeCutoff").toString()),
                new ReplayService.ReplayQuery(
                        List.of(new ReplayService.CoordQuery("npm", null, "late-lib", "2.0.0")),
                        List.of(), List.of()), "scenario");
        r.check("导出含相同输入且可重新计算出相同结论",
                buildId.equals(inputs.get("buildId"))
                        && itemsSignature(task1.id).equals(itemsSignature(recomputed.id)),
                "inputs.buildId=" + inputs.get("buildId"));
        r.objects.put("replayTaskId", task1.id);
        r.objects.put("lateSourceId", lock.id);
        return r;
    }

    /** 场景8：追溯修订 —— 更正版按生效与接收条件确定适用范围；后来确认的别名不解释此前清单。 */
    private ScenarioResult replayRevision() {
        ScenarioResult r = new ScenarioResult();
        r.scenario = "replay-revision";
        String buildId = newBuildId("revise");
        r.buildId = buildId;
        Instant t0 = Instant.now().minus(5, ChronoUnit.DAYS);
        Instant b = t0.plus(6, ChronoUnit.HOURS);

        SourceArtifact v1 = ingestFull("SBOM", "npm", buildId,
                "{\"components\":[{\"name\":\"legacy-lib\",\"version\":\"1.0.0\","
                        + "\"publisher\":\"old-team\",\"ecosystem\":\"npm\","
                        + "\"hashes\":[{\"alg\":\"SHA-256\",\"content\":\"1eg1b\"}]}]}",
                t0, "https://ci.corp/jobs/" + buildId + "/sbom-v1.json",
                null, null, null, null, null, null);
        r.steps.add("T0 摄入 SBOM v1：legacy-lib@1.0.0 publisher=old-team");

        SourceArtifact v2 = ingestFull("SBOM", "npm", buildId,
                "{\"components\":[{\"name\":\"legacy-lib\",\"version\":\"1.0.0\","
                        + "\"publisher\":\"new-team\",\"ecosystem\":\"npm\","
                        + "\"hashes\":[{\"alg\":\"SHA-256\",\"content\":\"1eg1b\"}]}]}",
                t0.plus(2, ChronoUnit.DAYS), "https://ci.corp/jobs/" + buildId + "/sbom-v2.json",
                v1.id, null, null, null, t0.minus(1, ChronoUnit.DAYS), null);
        r.steps.add("T0+2d 摄入更正版 v2（追溯生效 T0-1d）：publisher 更正为 new-team");

        ingestFull("SBOM", "npm", buildId,
                "{\"components\":[{\"name\":\"legacy-lib\",\"version\":\"1.0.0\","
                        + "\"publisher\":\"future-team\",\"ecosystem\":\"npm\","
                        + "\"hashes\":[{\"alg\":\"SHA-256\",\"content\":\"1eg1b\"}]}]}",
                t0.plus(3, ChronoUnit.DAYS), "https://ci.corp/jobs/" + buildId + "/sbom-v3.json",
                v2.id, null, null, null, t0.plus(10, ChronoUnit.DAYS), null);
        r.steps.add("T0+3d 摄入 v3（T0+10d 才生效）：publisher=future-team");

        ReplayTask replayA = replayService.replay(buildId, b, t0.plus(12, ChronoUnit.HOURS),
                ReplayService.ReplayQuery.empty(), "scenario");
        ReplayItem itemA = findItem(replayA.id, "legacy-lib");
        String pubA = itemA == null || itemA.identityId == null ? null
                : identityRepo.findById(itemA.identityId).map(i -> i.publisher).orElse(null);
        r.check("资料截止 T0+12h：适用 v1（old-team）",
                itemA != null && "PRESENT".equals(itemA.itemType) && "old-team".equals(pubA),
                "publisher=" + pubA);

        ReplayTask replayB = replayService.replay(buildId, b,
                t0.plus(2, ChronoUnit.DAYS).plus(12, ChronoUnit.HOURS),
                ReplayService.ReplayQuery.empty(), "scenario");
        ReplayItem itemB = findItem(replayB.id, "legacy-lib");
        String pubB = itemB == null || itemB.identityId == null ? null
                : identityRepo.findById(itemB.identityId).map(i -> i.publisher).orElse(null);
        r.check("资料截止 T0+2d+12h：追溯修订 v2 适用（new-team）",
                itemB != null && "PRESENT".equals(itemB.itemType) && "new-team".equals(pubB),
                "publisher=" + pubB);

        Map<String, Object> explainB = explainService.explain(itemB.id, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sourcesB = (List<Map<String, Object>>) explainB.get("applicableSources");
        r.check("v1 被 v2 取代并保留说明",
                sourcesB != null && sourcesB.stream()
                        .anyMatch(s -> v1.id.equals(s.get("id"))
                                && s.get("role").toString().contains("被取代")),
                "v1 role=" + sourcesB);

        ReplayTask replayC = replayService.replay(buildId, b, t0.plus(4, ChronoUnit.DAYS),
                new ReplayService.ReplayQuery(List.of(
                        new ReplayService.CoordQuery("npm", null, "old-legacy", null),
                        new ReplayService.CoordQuery("npm", null, "remote-old", null)),
                        List.of(), List.of()), "scenario");
        ReplayItem itemC = findItem(replayC.id, "legacy-lib");
        String pubC = itemC == null || itemC.identityId == null ? null
                : identityRepo.findById(itemC.identityId).map(i -> i.publisher).orElse(null);
        r.check("v3 在业务时点尚未生效，仍适用 v2",
                itemC != null && "new-team".equals(pubC), "publisher=" + pubC);

        // 后来确认的别名不能直接用于解释此前的清单（目录条目按名复用，场景可重复运行）
        var entry = entryRepo.findAll().stream()
                .filter(e -> "npm".equals(e.ecosystem) && "".equals(e.namespace)
                        && "legacy-lib".equals(e.name))
                .findFirst()
                .orElseGet(() -> catalogService.createEntry("npm", "", "legacy-lib",
                        "new-team", "[]", "scenario"));
        catalogService.reviseEntry(entry.id, null, "[\"old-legacy\"]", "补充历史别名（追溯生效）",
                t0.minus(1, ChronoUnit.DAYS), "scenario");
        var entry2 = entryRepo.findAll().stream()
                .filter(e -> "npm".equals(e.ecosystem) && "".equals(e.namespace)
                        && "remote-lib".equals(e.name))
                .findFirst()
                .orElseGet(() -> catalogService.createEntry("npm", "", "remote-lib",
                        "team", "[]", "scenario"));
        catalogService.reviseEntry(entry2.id, null, "[\"remote-old\"]", "别名 T0+10d 才生效",
                t0.plus(10, ChronoUnit.DAYS), "scenario");

        Map<String, String> aliasNow = replayService.aliasMapAt(b, Instant.now());
        r.check("追溯生效的别名可用于解释此前清单", "legacy-lib".equals(aliasNow.get("old-legacy")),
                "aliasMap=" + aliasNow.keySet());
        r.check("尚未生效的别名不用于解释此前清单", !aliasNow.containsKey("remote-old"),
                "aliasMap=" + aliasNow.keySet());
        Map<String, String> aliasEarly = replayService.aliasMapAt(b, t0.plus(1, ChronoUnit.DAYS));
        r.check("资料截止前未登记的别名不可用", !aliasEarly.containsKey("old-legacy"),
                "earlyAliasMap=" + aliasEarly.keySet());
        ReplayItem remoteOld = findItem(replayC.id, "remote-old");
        r.check("未生效别名查询按未知处理（不解释成没有）",
                remoteOld != null && "UNKNOWN".equals(remoteOld.itemType)
                        && "COVERAGE_INSUFFICIENT".equals(remoteOld.reasonCode),
                "remote-old=" + (remoteOld == null ? "无" : remoteOld.reasonCode));

        Map<String, Object> cmp = explainService.compare(replayA.id,
                t0.plus(2, ChronoUnit.DAYS).plus(12, ChronoUnit.HOURS), "scenario");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changes = (List<Map<String, Object>>) cmp.get("changes");
        boolean attributed = changes.stream()
                .filter(c -> c.get("coordinate").toString().contains("legacy-lib"))
                .findFirst()
                .map(c -> ((List<?>) c.get("attributions")).stream()
                        .anyMatch(a -> ((Map<?, ?>) a).get("id").equals(v2.id)))
                .orElse(false);
        r.check("publisher 变化归因到更正版 v2", attributed, "changes=" + changes);
        r.objects.put("replayTaskIds", List.of(replayA.id, replayB.id, replayC.id));
        return r;
    }

    /** 场景9：有效期间错开的关系不能拼出链路；同一原始记录的转抄只算一份独立支持。 */
    private ScenarioResult replayValidityCopies() {
        ScenarioResult r = new ScenarioResult();
        r.scenario = "replay-validity-copies";
        String buildId = newBuildId("validity");
        r.buildId = buildId;
        Instant t0 = Instant.now().minus(10, ChronoUnit.DAYS);
        Instant b = t0.plus(12, ChronoUnit.HOURS);

        ingestFull("REPO_METADATA", "npm", buildId,
                "{\"packages\":[{\"name\":\"mismatch-lib\",\"version\":\"2.0.0\","
                        + "\"publisher\":\"repo-team\",\"ecosystem\":\"npm\","
                        + "\"dist\":{\"integrity\":\"sha256-m1sm4tch\"}}]}",
                t0, "https://registry.npmjs.org/mismatch-lib",
                null, null, null, null, t0, t0.plus(1, ChronoUnit.DAYS));
        ingestFull("SIGNATURE_INDEX", "npm", buildId,
                "{\"signatures\":[{\"subjectHash\":\"m1sm4tch\",\"signer\":\"release-key\","
                        + "\"validFrom\":\"" + t0.plus(4, ChronoUnit.DAYS) + "\","
                        + "\"component\":{\"name\":\"mismatch-lib\",\"version\":\"2.0.0\","
                        + "\"ecosystem\":\"npm\",\"publisher\":\"repo-team\"}}]}",
                t0.plus(4, ChronoUnit.DAYS), "https://sigstore.corp/index/mismatch-lib",
                null, null, null, null, t0.plus(4, ChronoUnit.DAYS), null);
        r.steps.add("mismatch-lib：关系有效 [T0,T0+1d]，身份确认（签名）自 T0+4d 才生效");

        ingestFull("LOCKFILE", "npm", buildId,
                "{\"dependencies\":[{\"name\":\"overlap-lib\",\"version\":\"3.0.0\","
                        + "\"publisher\":\"repo-team\",\"ecosystem\":\"npm\","
                        + "\"integrity\":\"sha256-o1v3r1ap\"}]}",
                t0, "https://ci.corp/jobs/" + buildId + "/lock.json",
                null, null, null, null, t0, t0.plus(1, ChronoUnit.DAYS));
        r.steps.add("overlap-lib：锁文件关系与身份确认区间相交 [T0,T0+1d]");

        ingestFull("SBOM", "npm", buildId,
                "{\"components\":[{\"name\":\"copied-lib\",\"version\":\"1.0.0\","
                        + "\"publisher\":\"pub\",\"ecosystem\":\"npm\","
                        + "\"hashes\":[{\"alg\":\"SHA-256\",\"content\":\"c0p13d\"}]}]}",
                t0, "https://ci.corp/jobs/" + buildId + "/sbom.json",
                null, null, null, null, null, null);
        ingestFull("LOCKFILE", "npm", buildId,
                "{\"dependencies\":[{\"name\":\"copied-lib\",\"version\":\"1.0.0\","
                        + "\"publisher\":\"pub\",\"ecosystem\":\"npm\",\"integrity\":\"sha256-c0p13d\"}]}",
                t0, "https://ci.corp/jobs/" + buildId + "/lock-a.json",
                null, "orig-X", null, null, null, null);
        ingestFull("LOCKFILE", "npm", buildId,
                "{\"dependencies\":[{\"name\":\"copied-lib\",\"version\":\"1.0.0\","
                        + "\"publisher\":\"pub\",\"ecosystem\":\"npm\",\"integrity\":\"sha256-c0p13d\"}]}",
                t0, "https://mirror.corp/locks/lock-b.json",
                null, "orig-X", null, null, null, null);
        r.steps.add("copied-lib：1 份独立 SBOM + 同一原始记录转抄到 2 份锁文件");

        ReplayTask task = replayService.replay(buildId, b, t0.plus(20, ChronoUnit.DAYS),
                ReplayService.ReplayQuery.empty(), "scenario");
        r.steps.add("复演：业务时点 T0+12h，资料截止 T0+20d");

        ReplayItem mismatch = findItem(task.id, "mismatch-lib");
        r.check("有效区间无交集 → 未知（链路从未成立）",
                mismatch != null && "UNKNOWN".equals(mismatch.itemType)
                        && "VALIDITY_MISMATCH".equals(mismatch.reasonCode),
                "mismatch-lib=" + (mismatch == null ? "无" : mismatch.reasonCode));
        r.check("错开判断保留区间条件",
                mismatch != null && mismatch.conditions != null
                        && mismatch.conditions.contains("= ∅")
                        && mismatch.reasonDetail.contains("没有交集"),
                "conditions=" + (mismatch == null ? "无" : mismatch.conditions));

        ReplayItem overlap = findItem(task.id, "overlap-lib");
        r.check("有效区间相交 → 已证实出现",
                overlap != null && "PRESENT".equals(overlap.itemType),
                "overlap-lib=" + (overlap == null ? "无" : overlap.itemType));

        ReplayItem copied = findItem(task.id, "copied-lib");
        r.check("copied-lib 已证实出现", copied != null && "PRESENT".equals(copied.itemType),
                "copied-lib=" + (copied == null ? "无" : copied.itemType));
        Map<String, Object> explain = explainService.explain(copied.id, null);
        var derivation = (tools.jackson.databind.JsonNode) explain.get("derivation");
        tools.jackson.databind.JsonNode supportAny = null;
        for (tools.jackson.databind.JsonNode child : derivation.get("children")) {
            if ("SUPPORT".equals(child.get("role").asText())) {
                supportAny = child;
                break;
            }
        }
        int supporters = supportAny == null ? -1 : supportAny.get("children").size();
        r.check("同一原始记录的转抄只算一份独立支持（2 个而非 3 个支持来源）",
                supporters == 2, "supporters=" + supporters);
        r.check("转抄合并留有说明", copied.derivation.contains("转抄"),
                "derivation 含转抄说明=" + copied.derivation.contains("转抄"));
        r.objects.put("replayTaskId", task.id);
        return r;
    }

    /** 场景10：互斥身份与完整/残缺清单 —— 出现、缺席、未知、冲突逐项核对。 */
    private ScenarioResult replayExclusiveAbsence() {
        ScenarioResult r = new ScenarioResult();
        r.scenario = "replay-exclusive-absence";
        String buildId = newBuildId("absence");
        r.buildId = buildId;
        Instant t0 = Instant.now().minus(2, ChronoUnit.DAYS);
        Instant b = t0.plus(6, ChronoUnit.HOURS);

        ingestFull("SBOM", "npm", buildId,
                "{\"components\":["
                        + "{\"name\":\"core-lib\",\"version\":\"1.0.0\",\"publisher\":\"core-team\",\"ecosystem\":\"npm\",\"hashes\":[{\"alg\":\"SHA-256\",\"content\":\"c0r311b\"}]},"
                        + "{\"name\":\"conflict-lib\",\"version\":\"4.0.0\",\"publisher\":\"team-a\",\"ecosystem\":\"npm\",\"hashes\":[{\"alg\":\"SHA-256\",\"content\":\"hashA001\"}]},"
                        + "{\"name\":\"test-lib\",\"version\":\"1.0.0\",\"publisher\":\"core-team\",\"ecosystem\":\"npm\",\"scope\":\"test\",\"hashes\":[{\"alg\":\"SHA-256\",\"content\":\"t3st11b\"}]},"
                        + "{\"name\":\"nopub-lib\",\"version\":\"2.0.0\",\"ecosystem\":\"npm\",\"hashes\":[{\"alg\":\"SHA-256\",\"content\":\"n0pub1b\"}]}]}",
                t0, "https://ci.corp/jobs/" + buildId + "/sbom.json",
                null, null, "COMPLETE", "覆盖 runtime 全部直接依赖", null, null);
        r.steps.add("摄入完整 SBOM：core-lib / conflict-lib(hashA) / test-lib(scope=test) / nopub-lib(无发布者)");

        ingestFull("LOCKFILE", "npm", buildId,
                "{\"dependencies\":[{\"name\":\"conflict-lib\",\"version\":\"4.0.0\","
                        + "\"publisher\":\"team-a\",\"ecosystem\":\"npm\",\"integrity\":\"sha256-hashB002\"}]}",
                t0, "https://ci.corp/jobs/" + buildId + "/lock.json",
                null, null, "PARTIAL", "仅覆盖生产依赖，范围残缺", null, null);
        r.steps.add("摄入残缺锁文件：conflict-lib 哈希 hashB002（与 SBOM 冲突）");

        ingestFull("REPO_METADATA", "npm", buildId,
                "{\"packages\":[{\"name\":\"candidate-lib\",\"version\":\"1.0.0\","
                        + "\"publisher\":\"repo-team\",\"ecosystem\":\"npm\","
                        + "\"dist\":{\"integrity\":\"sha256-c4nd1date\"}}]}",
                t0, "https://registry.npmjs.org/candidate-lib", null, null, null, null, null, null);
        r.steps.add("摄入包仓元数据：candidate-lib 仅推断证据");

        ingestFull("SBOM", "npm", buildId,
                "{\"components\":[{\"name\":\"broken-lib\",\"version\":\"9.9.9\"",
                t0, "https://ci.corp/jobs/" + buildId + "/sbom-broken.json",
                null, null, null, null, null, null);
        r.steps.add("摄入损坏 SBOM（解析失败）：载荷文本提及 broken-lib");

        ReplayTask task = replayService.replay(buildId, b, t0.plus(1, ChronoUnit.DAYS),
                new ReplayService.ReplayQuery(List.of(
                        new ReplayService.CoordQuery("npm", null, "ghost-lib", null),
                        new ReplayService.CoordQuery("maven", null, "maybe-lib", null),
                        new ReplayService.CoordQuery("npm", null, "broken-lib", null),
                        new ReplayService.CoordQuery("npm", null, "test-lib", "1.0.0"),
                        new ReplayService.CoordQuery("npm", null, "nopub-lib", "2.0.0"),
                        new ReplayService.CoordQuery("npm", null, "candidate-lib", "1.0.0"),
                        new ReplayService.CoordQuery("npm", null, "conflict-lib", "4.0.0")),
                        List.of("test"), List.of("publisher")), "scenario");
        r.steps.add("复演：查询 7 个对象，筛选条件 excludeScopes=[test] requireFields=[publisher]");

        ReplayItem core = findItem(task.id, "core-lib");
        r.check("出现：core-lib 已证实", core != null && "PRESENT".equals(core.itemType),
                "core-lib=" + (core == null ? "无" : core.itemType));

        ReplayItem conflict = findItem(task.id, "conflict-lib");
        r.check("冲突：conflict-lib 互斥身份候选并存",
                conflict != null && "CONFLICT".equals(conflict.itemType)
                        && "MUTUALLY_EXCLUSIVE_IDENTITIES".equals(conflict.reasonCode),
                "conflict-lib=" + (conflict == null ? "无" : conflict.itemType));
        r.check("互斥条件保留且不合并证明",
                conflict != null && conflict.counterevidence != null
                        && conflict.counterevidence.contains("MUTUAL_EXCLUSION")
                        && conflict.conditions.contains("不得合并"),
                "counterevidence=" + (conflict == null ? "无" : conflict.counterevidence));

        ReplayItem ghost = findItem(task.id, "ghost-lib");
        r.check("缺席：完整来源确实未包含 ghost-lib",
                ghost != null && "ABSENT".equals(ghost.itemType)
                        && "NOT_IN_COMPLETE_SOURCE".equals(ghost.reasonCode),
                "ghost-lib=" + (ghost == null ? "无" : ghost.reasonCode));
        r.check("缺席结论保留来源完整性条件",
                ghost != null && ghost.conditions.contains("COMPLETE"),
                "conditions=" + (ghost == null ? "无" : ghost.conditions));

        ReplayItem maybe = findItem(task.id, "maybe-lib");
        r.check("未知：残缺清单不足以否定 maybe-lib（不把未知解释成没有）",
                maybe != null && "UNKNOWN".equals(maybe.itemType)
                        && "COVERAGE_INSUFFICIENT".equals(maybe.reasonCode),
                "maybe-lib=" + (maybe == null ? "无" : maybe.reasonCode));

        ReplayItem broken = findItem(task.id, "broken-lib");
        r.check("未知：broken-lib 文件尚未解析完整",
                broken != null && "UNKNOWN".equals(broken.itemType)
                        && "FILE_NOT_PARSED".equals(broken.reasonCode),
                "broken-lib=" + (broken == null ? "无" : broken.reasonCode));

        ReplayItem test = findItem(task.id, "test-lib");
        r.check("缺席：test-lib 被否定筛选条件排除",
                test != null && "ABSENT".equals(test.itemType)
                        && "EXCLUDED_BY_CONDITION".equals(test.reasonCode)
                        && test.conditions.contains("scope=test"),
                "test-lib=" + (test == null ? "无" : test.reasonCode));

        ReplayItem nopub = findItem(task.id, "nopub-lib");
        r.check("缺席：nopub-lib 空值判断排除（publisher 为空）",
                nopub != null && "ABSENT".equals(nopub.itemType)
                        && "EXCLUDED_BY_CONDITION".equals(nopub.reasonCode)
                        && nopub.conditions.contains("publisher 为空"),
                "nopub-lib=" + (nopub == null ? "无" : nopub.reasonCode));

        ReplayItem candidate = findItem(task.id, "candidate-lib");
        r.check("未知：candidate-lib 身份未决",
                candidate != null && "UNKNOWN".equals(candidate.itemType)
                        && "IDENTITY_UNDECIDED".equals(candidate.reasonCode),
                "candidate-lib=" + (candidate == null ? "无" : candidate.reasonCode));
        r.objects.put("replayTaskId", task.id);
        return r;
    }

    /** 场景11：多组替代支持 —— 精简组合不可再删减，预算提前停止时如实报告覆盖范围。 */
    private ScenarioResult replayMinimalEvidence() {
        ScenarioResult r = new ScenarioResult();
        r.scenario = "replay-minimal-evidence";
        String buildId = newBuildId("minimal");
        r.buildId = buildId;
        Instant t0 = Instant.now().minus(2, ChronoUnit.DAYS);
        Instant b = t0.plus(6, ChronoUnit.HOURS);

        ingestFull("SBOM", "npm", buildId,
                "{\"components\":[{\"name\":\"multi-lib\",\"version\":\"1.0.0\","
                        + "\"publisher\":\"pub\",\"ecosystem\":\"npm\","
                        + "\"hashes\":[{\"alg\":\"SHA-256\",\"content\":\"m1lt1\"}]}]}",
                t0, "https://ci.corp/jobs/" + buildId + "/sbom.json",
                null, null, null, null, null, null);
        ingestFull("LOCKFILE", "npm", buildId,
                "{\"dependencies\":[{\"name\":\"multi-lib\",\"version\":\"1.0.0\","
                        + "\"publisher\":\"pub\",\"ecosystem\":\"npm\",\"integrity\":\"sha256-m1lt1\"}]}",
                t0, "https://ci.corp/jobs/" + buildId + "/lock.json",
                null, null, null, null, null, null);
        ingestFull("BUILD_LOG", "npm", buildId,
                "{\"resolved\":[{\"name\":\"multi-lib\",\"version\":\"1.0.0\","
                        + "\"publisher\":\"pub\",\"ecosystem\":\"npm\",\"sha256\":\"m1lt1\"}]}",
                t0, "https://ci.corp/jobs/" + buildId + "/build.log",
                null, null, null, null, null, null);
        r.steps.add("multi-lib：3 份相互独立的解析证据（SBOM / 锁文件 / 构建日志）");

        ReplayTask task = replayService.replay(buildId, b, t0.plus(1, ChronoUnit.DAYS),
                ReplayService.ReplayQuery.empty(), "scenario");
        ReplayItem multi = findItem(task.id, "multi-lib");
        r.check("multi-lib 已证实出现", multi != null && "PRESENT".equals(multi.itemType),
                "multi-lib=" + (multi == null ? "无" : multi.itemType));

        Map<String, Object> full = explainService.explain(multi.id, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> minimal = (Map<String, Object>) full.get("minimal");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> combos = (List<Map<String, Object>>) minimal.get("combinations");
        r.check("精简组合覆盖全部替代支持（3 来源 × 3 身份依据 = 9 组）",
                combos.size() == 9 && Boolean.TRUE.equals(minimal.get("exhaustive")),
                "combos=" + combos.size() + " exhaustive=" + minimal.get("exhaustive"));
        boolean allIrreducible = combos.stream()
                .allMatch(c -> ((List<?>) c.get("leaves")).size() == 4
                        && Boolean.TRUE.equals(c.get("verified")));
        r.check("每组均核实为不可再删减（构建记录+关系原文+包身份+有效区间缺一不可）",
                allIrreducible, "sizes=" + combos.stream().map(c -> c.get("size")).toList());

        Map<String, Object> limited = explainService.explain(multi.id, 5);
        @SuppressWarnings("unchecked")
        Map<String, Object> limMinimal = (Map<String, Object>) limited.get("minimal");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> limCombos = (List<Map<String, Object>>) limMinimal.get("combinations");
        @SuppressWarnings("unchecked")
        List<String> unchecked = (List<String>) limMinimal.get("uncheckedBranches");
        r.check("预算提前停止：返回已核实组合与未检查分支，不声称穷尽",
                !limCombos.isEmpty() && limCombos.size() < 9
                        && Boolean.FALSE.equals(limMinimal.get("exhaustive"))
                        && !unchecked.isEmpty(),
                "combos=" + limCombos.size() + " unchecked=" + unchecked.size());
        r.check("覆盖范围说明准确（未穷尽且不否定记录）",
                limMinimal.get("coverageNote").toString().contains("未穷尽")
                        && limMinimal.get("coverageNote").toString().contains("不否定"),
                "note=" + limMinimal.get("coverageNote"));

        Map<String, Object> zero = explainService.explain(multi.id, 0);
        @SuppressWarnings("unchecked")
        Map<String, Object> zeroMinimal = (Map<String, Object>) zero.get("minimal");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> zeroCombos = (List<Map<String, Object>>) zeroMinimal.get("combinations");
        ReplayItem stillPresent = findItem(task.id, "multi-lib");
        r.check("预算为零未找到组合，也不否定已算出的记录",
                zeroCombos.isEmpty() && stillPresent != null
                        && "PRESENT".equals(stillPresent.itemType),
                "zeroCombos=" + zeroCombos.size() + " item=" + stillPresent.itemType);
        r.objects.put("replayTaskId", task.id);
        return r;
    }
}
