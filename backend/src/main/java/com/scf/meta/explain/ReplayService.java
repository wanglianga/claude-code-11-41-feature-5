package com.scf.meta.explain;

import com.scf.meta.catalog.CatalogEntry;
import com.scf.meta.catalog.CatalogEntryRepository;
import com.scf.meta.catalog.CatalogRevision;
import com.scf.meta.catalog.CatalogRevisionRepository;
import com.scf.meta.common.Json;
import com.scf.meta.identity.ComponentClaim;
import com.scf.meta.identity.ComponentClaimRepository;
import com.scf.meta.identity.ComponentIdentity;
import com.scf.meta.identity.ComponentIdentityRepository;
import com.scf.meta.review.ReviewService;
import com.scf.meta.signature.SignatureRecord;
import com.scf.meta.signature.SignatureRecordRepository;
import com.scf.meta.source.SourceArtifact;
import com.scf.meta.source.SourceArtifactRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 时点复演引擎：固定业务时点 B、资料截止时间 K、规则版本与查询条件后，
 * 先在两个时点下选出可用事实（修订链按生效与接收条件适用、同一原始记录的
 * 转抄只算一份独立支持），再完成关联（依赖边与组件身份的有效区间必须有交集）。
 * 输出出现 / 缺席 / 未知 / 冲突条目，并为每条记录保存完整推导图。
 */
@Service
public class ReplayService {

    /** 业务有效区间（null 端表示不限）。 */
    public record Interval(Instant from, Instant to) {
        public static final Interval OPEN = new Interval(null, null);

        boolean contains(Instant t) {
            return (from == null || !t.isBefore(from)) && (to == null || !t.isAfter(to));
        }

        Interval intersect(Interval o) {
            Instant f = from == null ? o.from : (o.from == null ? from : (from.isAfter(o.from) ? from : o.from));
            Instant t = to == null ? o.to : (o.to == null ? to : (to.isBefore(o.to) ? to : o.to));
            if (f != null && t != null && f.isAfter(t)) return null;
            return new Interval(f, t);
        }

        /** 展示用并集：取两端极值（仅用于区间显示，不用于相交判断）。 */
        Interval union(Interval o) {
            Instant f = from == null || o.from == null ? null : (from.isBefore(o.from) ? from : o.from);
            Instant t = to == null || o.to == null ? null : (to.isAfter(o.to) ? to : o.to);
            return new Interval(f, t);
        }

        @Override
        public String toString() {
            return "[" + (from == null ? "-∞" : from.toString()) + ", "
                    + (to == null ? "+∞" : to.toString()) + "]";
        }
    }

    /** 缺席对象查询坐标。 */
    public record CoordQuery(String ecosystem, String namespace, String name, String version) {}

    /** 复演查询条件：缺席对象 + 否定筛选 + 必填字段。 */
    public record ReplayQuery(List<CoordQuery> queries, List<String> excludeScopes,
                              List<String> requireFields) {
        public static ReplayQuery empty() {
            return new ReplayQuery(List.of(), List.of(), List.of());
        }
    }

    private final SourceArtifactRepository sourceRepo;
    private final ComponentClaimRepository claimRepo;
    private final ComponentIdentityRepository identityRepo;
    private final SignatureRecordRepository signatureRepo;
    private final CatalogEntryRepository entryRepo;
    private final CatalogRevisionRepository revisionRepo;
    private final ReplayTaskRepository taskRepo;
    private final ReplayItemRepository itemRepo;
    private final ReviewService reviewService;
    private final Json json;
    private final String ruleVersion;

    public ReplayService(SourceArtifactRepository sourceRepo,
                         ComponentClaimRepository claimRepo,
                         ComponentIdentityRepository identityRepo,
                         SignatureRecordRepository signatureRepo,
                         CatalogEntryRepository entryRepo,
                         CatalogRevisionRepository revisionRepo,
                         ReplayTaskRepository taskRepo,
                         ReplayItemRepository itemRepo,
                         ReviewService reviewService, Json json,
                         @Value("${scf.rule-version}") String ruleVersion) {
        this.sourceRepo = sourceRepo;
        this.claimRepo = claimRepo;
        this.identityRepo = identityRepo;
        this.signatureRepo = signatureRepo;
        this.entryRepo = entryRepo;
        this.revisionRepo = revisionRepo;
        this.taskRepo = taskRepo;
        this.itemRepo = itemRepo;
        this.reviewService = reviewService;
        this.json = json;
        this.ruleVersion = ruleVersion;
    }

    // ------------------------------------------------------------------
    // 来源选择：修订链按生效与接收条件适用
    // ------------------------------------------------------------------

    /** 一次复演的来源选择结果。 */
    public static class SourceSelection {
        public final List<SourceArtifact> usable = new ArrayList<>();
        /** 被取代来源 id -> 取代它的来源 id */
        public final Map<String, String> supersededBy = new LinkedHashMap<>();
        /** 已接收但在业务时点尚未生效的修订 */
        public final List<SourceArtifact> notYetEffective = new ArrayList<>();
        /** 资料截止时间之后到达的来源 */
        public final List<SourceArtifact> late = new ArrayList<>();
        /** 已接收但未解析完整的来源 */
        public final List<SourceArtifact> unparsed = new ArrayList<>();
    }

    /**
     * 修订链适用：已接收（collectedAt<=K）且在业务时点已生效（validFrom<=B，空表示始终生效）
     * 的最新修订取代旧版；同一原始记录的转抄不影响选择，只在计独立支持时去重。
     */
    public SourceSelection selectSources(List<SourceArtifact> all, Instant businessTime,
                                         Instant knowledgeCutoff) {
        SourceSelection sel = new SourceSelection();
        Map<String, SourceArtifact> byId = all.stream()
                .collect(Collectors.toMap(s -> s.id, s -> s, (a, b) -> a, LinkedHashMap::new));
        List<SourceArtifact> received = all.stream()
                .filter(s -> !s.collectedAt.isAfter(knowledgeCutoff)).toList();
        sel.late.addAll(all.stream().filter(s -> s.collectedAt.isAfter(knowledgeCutoff)).toList());

        // 按修订链分组（链根 = 沿 revisesId 走到头）
        Map<String, List<SourceArtifact>> chains = new LinkedHashMap<>();
        for (SourceArtifact s : received) {
            String root = s.id;
            Set<String> seen = new LinkedHashSet<>();
            SourceArtifact cur = s;
            while (cur.revisesId != null && byId.containsKey(cur.revisesId)
                    && seen.add(cur.revisesId)) {
                cur = byId.get(cur.revisesId);
                root = cur.id;
            }
            chains.computeIfAbsent(root, k -> new ArrayList<>()).add(s);
        }
        for (List<SourceArtifact> chain : chains.values()) {
            // 生效时间：显式 validFrom 优先，空表示始终生效（-∞）
            record Eff(SourceArtifact s, Instant eff) {}
            List<Eff> candidates = chain.stream()
                    .map(s -> new Eff(s, s.validFrom != null ? s.validFrom : Instant.MIN))
                    .sorted(Comparator.comparing(Eff::eff).thenComparing(e -> e.s.collectedAt))
                    .toList();
            SourceArtifact selected = null;
            for (Eff e : candidates) {
                if (!e.eff.isAfter(businessTime)) selected = e.s;   // 不晚于 B 的最后生效者
            }
            for (Eff e : candidates) {
                if (selected != null && e.s.id.equals(selected.id)) {
                    sel.usable.add(e.s);
                } else if (e.eff.isAfter(businessTime)) {
                    sel.notYetEffective.add(e.s);
                } else {
                    sel.supersededBy.put(e.s.id, selected == null ? null : selected.id);
                }
            }
        }
        sel.usable.sort(Comparator.comparing(s -> s.collectedAt));
        for (SourceArtifact s : sel.usable) {
            if (!SourceArtifact.IngestStatus.PARSED.name().equals(s.ingestStatus)
                    && !SourceArtifact.IngestStatus.PARSED_WITH_WARNINGS.name().equals(s.ingestStatus)) {
                sel.unparsed.add(s);
            }
        }
        return sel;
    }

    // ------------------------------------------------------------------
    // 复演主流程
    // ------------------------------------------------------------------

    @Transactional
    public ReplayTask replay(String buildId, Instant businessTime, Instant knowledgeCutoff,
                             ReplayQuery query, String actor) {
        Objects.requireNonNull(businessTime, "业务时点不能为空");
        Objects.requireNonNull(knowledgeCutoff, "资料截止时间不能为空");
        ReplayQuery q = query == null ? ReplayQuery.empty() : query;
        List<SourceArtifact> all = sourceRepo.findByBuildIdOrderByCollectedAtAsc(buildId);
        if (all.isEmpty()) {
            throw new IllegalArgumentException("构建 " + buildId + " 没有任何来源，无法复演");
        }

        ReplayTask task = new ReplayTask();
        task.buildId = buildId;
        task.businessTime = businessTime;
        task.knowledgeCutoff = knowledgeCutoff;
        task.ruleVersion = ruleVersion;
        task.queryJson = json.write(q);
        task.createdBy = actor;
        taskRepo.save(task);

        // 第一步：在两个时点下选出可用事实
        SourceSelection sel = selectSources(all, businessTime, knowledgeCutoff);
        Set<String> usableSourceIds = sel.usable.stream().map(s -> s.id).collect(Collectors.toSet());
        Map<String, SourceArtifact> sourceById = new HashMap<>();
        all.forEach(s -> sourceById.put(s.id, s));

        List<ComponentClaim> kClaims = claimRepo.findByBuildContext(buildId).stream()
                .filter(c -> usableSourceIds.contains(c.sourceArtifactId)).toList();

        // 查询条件中的否定筛选与必填字段（空值判断保留为条件）
        List<ComponentClaim> filtered = new ArrayList<>();
        Map<String, String> filterExcluded = new LinkedHashMap<>();   // claimId -> 排除原因
        for (ComponentClaim c : kClaims) {
            String exclusion = filterExclusion(c, q);
            if (exclusion != null) filterExcluded.put(c.id, exclusion);
            else filtered.add(c);
        }

        // 业务时点有效：关系区间包含 B
        List<ComponentClaim> bValid = filtered.stream()
                .filter(c -> claimInterval(c, sourceById.get(c.sourceArtifactId)).contains(businessTime))
                .toList();

        // 身份状态按 rules-1.0 在 K 时点重算（使用全部通过筛选的声明，不限于 B 有效）
        Map<String, List<ComponentClaim>> groups = filtered.stream()
                .collect(Collectors.groupingBy(ComponentClaim::groupKey, LinkedHashMap::new,
                        Collectors.toList()));
        Map<String, GroupResolution> resolutions = new LinkedHashMap<>();
        for (Map.Entry<String, List<ComponentClaim>> e : groups.entrySet()) {
            resolutions.put(e.getKey(), resolveGroup(e.getValue(), sourceById, knowledgeCutoff));
        }

        // 第二步：关联 —— 依赖边与组件身份的有效区间必须有交集
        List<ReplayItem> items = new ArrayList<>();
        Map<String, List<ComponentClaim>> bValidByGroup = bValid.stream()
                .collect(Collectors.groupingBy(ComponentClaim::groupKey, LinkedHashMap::new,
                        Collectors.toList()));
        for (Map.Entry<String, GroupResolution> e : resolutions.entrySet()) {
            List<ComponentClaim> relationClaims = bValidByGroup.getOrDefault(e.getKey(), List.of());
            if (relationClaims.isEmpty()) continue;   // 业务时点无有效关系，不构成清单条目
            GroupResolution gr = e.getValue();
            List<SubIdentity> conflictSubs = gr.identities.stream()
                    .filter(s -> "CONFLICT".equals(s.status)).toList();
            if (!conflictSubs.isEmpty()) {
                items.add(buildConflictItem(task, e.getKey(), gr, conflictSubs, relationClaims,
                        sourceById));
            }
            for (SubIdentity sub : gr.identities) {
                if ("CONFLICT".equals(sub.status)) continue;
                List<ComponentClaim> ownRelation = relationClaims.stream()
                        .filter(sub::matches).toList();
                if (ownRelation.isEmpty()) continue;
                items.add(buildPresentOrUndecided(task, e.getKey(), sub, ownRelation,
                        sourceById, businessTime));
            }
        }

        // 缺席对象评估：明确指定的缺席对象逐项分类
        Map<String, String> aliasMap = aliasMapAt(businessTime, knowledgeCutoff);
        for (CoordQuery cq : q.queries()) {
            String key = coordKey(cq.ecosystem(), cq.namespace(), cq.name(), cq.version());
            // 已有输出记录（含经目录别名匹配到的记录）时，解释关联该记录而非缺席评估
            boolean linked = items.stream().anyMatch(i -> queryMatchesKey(i.coordKey, cq, aliasMap));
            if (linked) continue;
            items.add(evaluateAbsence(task, cq, key, sel, sourceById, q, filterExcluded,
                    businessTime, knowledgeCutoff, aliasMap));
        }

        // 汇总与保存
        int present = 0, absent = 0, unknown = 0, conflict = 0;
        for (ReplayItem item : items) {
            itemRepo.save(item);
            switch (item.itemType) {
                case "PRESENT" -> present++;
                case "ABSENT" -> absent++;
                case "CONFLICT" -> conflict++;
                default -> unknown++;
            }
        }
        task.summary = "present=" + present + " absent=" + absent + " unknown=" + unknown
                + " conflict=" + conflict + " sources=" + sel.usable.size()
                + " late=" + sel.late.size();
        taskRepo.save(task);
        reviewService.record("REPLAY", task.id, "REPLAYED",
                "build=" + buildId + " B=" + businessTime + " K=" + knowledgeCutoff + " "
                        + task.summary, actor);
        return task;
    }

    // ------------------------------------------------------------------
    // 身份重算（rules-1.0，K 时点视角）
    // ------------------------------------------------------------------

    /** 组内一个身份候选及其在 K 时点的确认依据。 */
    public static class SubIdentity {
        public String identityKey;
        public String publisher;
        public String hashValue;
        public String status;                 // CONFIRMED / CANDIDATE / CONFLICT / INSUFFICIENT_EVIDENCE
        public String identityId;             // 持久化身份 id（若存在）
        public List<ComponentClaim> confirmClaims = new ArrayList<>();  // 确认依据声明（解析证据）
        public SignatureRecord confirmSignature;                        // 或 K 时点有效的签名
        public Interval confirmInterval = Interval.OPEN;                // 身份确认的业务有效区间
        public String statusReason;

        boolean matches(ComponentClaim c) {
            return Objects.equals(nullToEmpty(publisher), nullToEmpty(c.publisher))
                    && Objects.equals(hashValue, c.hashValue);
        }

        private static String nullToEmpty(String s) {
            return s == null ? "" : s;
        }
    }

    public static class GroupResolution {
        public String conflictReason;
        public final List<SubIdentity> identities = new ArrayList<>();
    }

    private GroupResolution resolveGroup(List<ComponentClaim> claims,
                                         Map<String, SourceArtifact> sourceById, Instant k) {
        GroupResolution gr = new GroupResolution();
        Map<String, SubIdentity> subs = new LinkedHashMap<>();
        for (ComponentClaim c : claims) {
            String key = ComponentIdentity.keyOf(c.ecosystem, c.namespace, c.name, c.version,
                    c.publisher, c.hashValue);
            subs.computeIfAbsent(key, kk -> {
                SubIdentity s = new SubIdentity();
                s.identityKey = kk;
                s.publisher = c.publisher;
                s.hashValue = c.hashValue;
                identityRepo.findByIdentityKey(kk).ifPresent(i -> s.identityId = i.id);
                return s;
            });
        }
        List<ComponentClaim> resolved = claims.stream()
                .filter(c -> ComponentClaim.Kind.RESOLVED.name().equals(c.kind)).toList();
        Set<String> resolvedHashes = resolved.stream().map(c -> c.hashValue)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<String> buildLogHashes = resolved.stream()
                .filter(c -> {
                    SourceArtifact s = sourceById.get(c.sourceArtifactId);
                    return s != null && "BUILD_LOG".equals(s.type);
                })
                .map(c -> c.hashValue).filter(Objects::nonNull).collect(Collectors.toSet());
        boolean hashConflict = resolvedHashes.size() > 1;

        for (SubIdentity sub : subs.values()) {
            List<ComponentClaim> own = claims.stream().filter(sub::matches).toList();
            List<ComponentClaim> ownResolved = own.stream()
                    .filter(c -> ComponentClaim.Kind.RESOLVED.name().equals(c.kind)).toList();
            SignatureRecord validSig = validSignatureAt(sub.hashValue, k, sourceById);

            if (hashConflict) {
                if (sub.hashValue != null && buildLogHashes.contains(sub.hashValue)) {
                    sub.status = "CONFIRMED";
                    sub.confirmClaims = ownResolved;
                    sub.confirmInterval = spanOf(ownResolved, sourceById);
                } else {
                    sub.status = "CONFLICT";
                }
            } else if (!ownResolved.isEmpty()) {
                sub.status = "CONFIRMED";
                sub.confirmClaims = ownResolved;
                sub.confirmInterval = spanOf(ownResolved, sourceById);
            } else if (validSig != null) {
                sub.status = "CONFIRMED";
                sub.confirmSignature = validSig;
                sub.confirmInterval = new Interval(validSig.validFrom, null);
            } else if (signatureRevokedBefore(sub.hashValue, k)) {
                sub.status = "INSUFFICIENT_EVIDENCE";
                sub.statusReason = "唯一依据的签名已在资料截止时间前撤销，身份证据不足";
            } else {
                sub.status = "CANDIDATE";
                sub.statusReason = "仅推断证据（包仓/扫描），身份未确认";
            }
        }
        List<SubIdentity> conflictSubs = subs.values().stream()
                .filter(s -> "CONFLICT".equals(s.status)).toList();
        if (!conflictSubs.isEmpty()) {
            gr.conflictReason = "坐标哈希在解析证据间不一致 " + resolvedHashes
                    + "，构建日志无法证明该哈希被实际解析";
        }
        gr.identities.addAll(subs.values());
        return gr;
    }

    /** K 时点有效的签名：来源已接收且未在 K 前撤销。 */
    private SignatureRecord validSignatureAt(String hash, Instant k,
                                             Map<String, SourceArtifact> sourceById) {
        if (hash == null) return null;
        return signatureRepo.findBySubjectHash(hash).stream()
                .filter(s -> {
                    SourceArtifact src = sourceById.get(s.sourceArtifactId);
                    return src == null || !src.collectedAt.isAfter(k);
                })
                .filter(s -> s.revokedAt == null || s.revokedAt.isAfter(k))
                .findFirst().orElse(null);
    }

    private boolean signatureRevokedBefore(String hash, Instant k) {
        if (hash == null) return false;
        List<SignatureRecord> sigs = signatureRepo.findBySubjectHash(hash);
        return !sigs.isEmpty() && sigs.stream().allMatch(s -> s.revokedAt != null
                && !s.revokedAt.isAfter(k));
    }

    /** 确认证据的展示区间（各路径区间的并集，仅用于显示）。 */
    private Interval spanOf(List<ComponentClaim> claims, Map<String, SourceArtifact> byId) {
        Interval acc = null;
        for (ComponentClaim c : claims) {
            Interval i = claimInterval(c, byId.get(c.sourceArtifactId));
            acc = acc == null ? i : acc.union(i);
        }
        return acc == null ? Interval.OPEN : acc;
    }

    /** 声明的业务有效区间：声明级覆盖来源级。 */
    public Interval claimInterval(ComponentClaim c, SourceArtifact source) {
        Instant from = c.validFrom != null ? c.validFrom : (source != null ? source.validFrom : null);
        Instant to = c.validTo != null ? c.validTo : (source != null ? source.validTo : null);
        return new Interval(from, to);
    }

    // ------------------------------------------------------------------
    // 条目构建
    // ------------------------------------------------------------------

    private ReplayItem buildConflictItem(ReplayTask task, String groupKey, GroupResolution gr,
                                         List<SubIdentity> conflictSubs,
                                         List<ComponentClaim> relationClaims,
                                         Map<String, SourceArtifact> sourceById) {
        ReplayItem item = newItem(task, groupKey, "CONFLICT");
        item.reasonCode = ReplayItem.Reason.MUTUALLY_EXCLUSIVE_IDENTITIES.name();
        item.reasonDetail = gr.conflictReason + "；互斥候选各自保留成立条件，不合并证明共同可达";

        List<EvidenceNode> branches = new ArrayList<>();
        List<Map<String, Object>> counter = new ArrayList<>();
        int idx = 1;
        for (SubIdentity sub : conflictSubs) {
            List<ComponentClaim> own = relationClaims.stream().filter(sub::matches).toList();
            String cand = "候选" + idx + "（hash=" + display(sub.hashValue) + " publisher="
                    + display(sub.publisher) + "）";
            List<EvidenceNode> andChildren = new ArrayList<>();
            for (ComponentClaim c : own) {
                SourceArtifact s = sourceById.get(c.sourceArtifactId);
                EvidenceNode leaf = claimLeaf("RELATION_TEXT", "关系原文：" + s.type + " 中的声明", c, s);
                leaf.assumptions = List.of(groupKey + "|" + sub.identityKey);
                andChildren.add(leaf);
            }
            if (andChildren.isEmpty()) {
                EvidenceNode cond = EvidenceNode.leaf("CONDITION", "该候选在业务时点无有效关系声明",
                        "CONDITION", null, null, null);
                cond.assumptions = List.of(groupKey + "|" + sub.identityKey);
                andChildren.add(cond);
            }
            EvidenceNode branch = EvidenceNode.all("SUPPORT", cand, andChildren);
            branch.assumptions = List.of(groupKey + "|" + sub.identityKey);
            branches.add(branch);
            idx++;
        }
        for (int i = 0; i < conflictSubs.size(); i++) {
            for (int j = i + 1; j < conflictSubs.size(); j++) {
                SubIdentity a = conflictSubs.get(i);
                SubIdentity d = conflictSubs.get(j);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("kind", "MUTUAL_EXCLUSION");
                m.put("detail", "候选 hash=" + display(a.hashValue) + " 与 hash="
                        + display(d.hashValue) + " 互斥：" + gr.conflictReason);
                counter.add(m);
            }
        }
        item.derivation = writeJson(EvidenceNode.any("EDGE",
                "互斥身份候选（各自成立条件分别保留）", true, branches));
        item.conditions = writeJson(List.of("互斥条件：不同候选下的边不得合并证明共同可达",
                gr.conflictReason == null ? "" : gr.conflictReason));
        item.counterevidence = writeJson(counter);
        return item;
    }

    private ReplayItem buildPresentOrUndecided(ReplayTask task, String groupKey, SubIdentity sub,
                                               List<ComponentClaim> relationClaims,
                                               Map<String, SourceArtifact> sourceById, Instant b) {
        ReplayItem item = newItem(task, groupKey, "PRESENT");
        item.identityId = sub.identityId;
        Interval relationInterval = spanOf(relationClaims, sourceById);
        List<String> conditions = new ArrayList<>();

        // 独立支持：同一原始记录的转抄合并为一个来源
        Map<String, List<ComponentClaim>> byOrigin = relationClaims.stream()
                .filter(c -> ComponentClaim.Kind.RESOLVED.name().equals(c.kind)
                        || ComponentClaim.Kind.SIGNATURE.name().equals(c.kind))
                .collect(Collectors.groupingBy(c -> {
                    SourceArtifact s = sourceById.get(c.sourceArtifactId);
                    return s != null && s.originId != null ? s.originId : c.sourceArtifactId;
                }, LinkedHashMap::new, Collectors.toList()));

        if (!"CONFIRMED".equals(sub.status)) {
            item.itemType = "UNKNOWN";
            item.reasonCode = ReplayItem.Reason.IDENTITY_UNDECIDED.name();
            item.reasonDetail = sub.statusReason != null ? sub.statusReason : "身份未决";
            conditions.add("未决原因：" + item.reasonDetail);
            item.conditions = writeJson(conditions);
            item.derivation = writeJson(EvidenceNode.all("EDGE",
                    "身份未决：" + item.reasonDetail,
                    List.of(relationAnyNode(byOrigin, sourceById))));
            item.counterevidence = "[]";
            return item;
        }

        // 有效区间交集：逐条确认路径与关系区间求交，存在非空交集才能拼出链路
        Interval overlap = null;
        for (ComponentClaim c : sub.confirmClaims) {
            Interval o = claimInterval(c, sourceById.get(c.sourceArtifactId)).intersect(relationInterval);
            if (o != null) overlap = overlap == null ? o : overlap.union(o);
        }
        if (sub.confirmSignature != null) {
            Interval o = new Interval(sub.confirmSignature.validFrom, null).intersect(relationInterval);
            if (o != null) overlap = overlap == null ? o : overlap.union(o);
        }
        conditions.add("有效区间：关系区间 " + relationInterval + " 须与身份确认区间 "
                + sub.confirmInterval + " 相交");
        if (overlap == null) {
            item.itemType = "UNKNOWN";
            item.reasonCode = ReplayItem.Reason.VALIDITY_MISMATCH.name();
            item.reasonDetail = "依赖边有效区间 " + relationInterval + " 与身份确认区间 "
                    + sub.confirmInterval + " 没有交集，不能拼出一条从未成立过的链路";
            conditions.add("有效区间判断：关系 " + relationInterval + " ∩ 身份 " + sub.confirmInterval
                    + " = ∅");
            item.conditions = writeJson(conditions);
            item.derivation = writeJson(EvidenceNode.all("EDGE",
                    "有效区间错开，链路从未成立",
                    List.of(relationAnyNode(byOrigin, sourceById),
                            identityAnyNode(sub, sourceById))));
            item.counterevidence = "[]";
            return item;
        }

        if (byOrigin.isEmpty()) {
            // 仅推断证据，不能证实出现
            item.itemType = "UNKNOWN";
            item.reasonCode = ReplayItem.Reason.IDENTITY_UNDECIDED.name();
            item.reasonDetail = "仅推断证据（包仓/扫描），无实际解析证据支持出现";
            conditions.add("未决原因：缺少实际解析证据（SBOM/锁文件/构建日志/签名）");
            item.conditions = writeJson(conditions);
            item.derivation = writeJson(EvidenceNode.all("EDGE", "仅推断证据",
                    List.of(relationAnyNode(byOrigin, sourceById))));
            item.counterevidence = "[]";
            return item;
        }

        // PRESENT：共同必需 = 独立支持来源（任一） + 有效区间条件
        item.reasonCode = ReplayItem.Reason.PROVEN.name();
        item.reasonDetail = "在业务时点 " + b + "、资料截止 " + task.knowledgeCutoff
                + " 下由实际解析证据证实出现";
        conditions.add("资料截止判断：全部依据来源收集时间不晚于 " + task.knowledgeCutoff);
        item.conditions = writeJson(conditions);

        List<EvidenceNode> supporters = new ArrayList<>();
        for (Map.Entry<String, List<ComponentClaim>> e : byOrigin.entrySet()) {
            SourceArtifact rep = sourceById.get(e.getValue().get(0).sourceArtifactId);
            String copyNote = copyNote(e.getValue());
            List<EvidenceNode> andChildren = new ArrayList<>();
            andChildren.add(EvidenceNode.leaf("BUILD_RECORD",
                    "构建记录：" + rep.type + "（收集于 " + rep.collectedAt + "）" + copyNote,
                    "SOURCE", rep.id, rep.sourceUri, intervalOf(rep).toString()));
            for (ComponentClaim c : e.getValue()) {
                andChildren.add(claimLeaf("RELATION_TEXT",
                        "关系原文：" + rep.type + " 声明 " + c.name + "@" + display(c.version), c, rep));
            }
            andChildren.add(identityAnyNode(sub, sourceById));
            supporters.add(EvidenceNode.all("SUPPORT",
                    "来源 " + rep.type + "#" + rep.id.substring(0, 8) + " 单独提供完整依据" + copyNote,
                    andChildren));
        }
        EvidenceNode validityLeaf = EvidenceNode.leaf("VALIDITY",
                "有效区间交集 " + overlap + " 非空（关系 " + relationInterval + " ∩ 身份 "
                        + sub.confirmInterval + "）",
                "CONDITION", null, null, overlap.toString());
        EvidenceNode root = EvidenceNode.all("EDGE",
                "build:" + task.buildId + " → " + coordinateOf(groupKey) + " 在 (B,K) 成立",
                List.of(EvidenceNode.any("SUPPORT",
                        "独立支持来源（任一单独成立；同一原始记录的转抄已合并）", false, supporters),
                        validityLeaf));
        item.derivation = writeJson(root);
        item.counterevidence = writeJson(collectCounterevidence(sub));
        return item;
    }

    // ------------------------------------------------------------------
    // 缺席评估
    // ------------------------------------------------------------------

    private ReplayItem evaluateAbsence(ReplayTask task, CoordQuery cq, String key,
                                       SourceSelection sel, Map<String, SourceArtifact> sourceById,
                                       ReplayQuery q, Map<String, String> filterExcluded,
                                       Instant b, Instant k, Map<String, String> aliasMap) {
        ReplayItem item = newItem(task, key, "UNKNOWN");
        List<String> conditions = new ArrayList<>();
        List<Map<String, Object>> counter = new ArrayList<>();

        // 匹配该坐标的全部声明（含被筛选/有效性排除的），别名按 (B,K) 展开
        List<ComponentClaim> mentions = claimRepo.findByBuildContext(task.buildId).stream()
                .filter(c -> queryMatches(c, cq, aliasMap))
                .toList();
        Set<String> usableSourceIds = sel.usable.stream().map(s -> s.id).collect(Collectors.toSet());
        List<ComponentClaim> usableMentions = mentions.stream()
                .filter(c -> usableSourceIds.contains(c.sourceArtifactId)).toList();
        List<ComponentClaim> filterExcludedMentions = usableMentions.stream()
                .filter(c -> filterExcluded.containsKey(c.id)).toList();
        List<ComponentClaim> activeMentions = usableMentions.stream()
                .filter(c -> !filterExcluded.containsKey(c.id)).toList();

        // 1) 声明存在但全部被查询条件排除（否定筛选 / 空值判断）→ 被具体条件排除
        if (!filterExcludedMentions.isEmpty() && activeMentions.isEmpty()) {
            item.itemType = "ABSENT";
            item.reasonCode = ReplayItem.Reason.EXCLUDED_BY_CONDITION.name();
            List<EvidenceNode> children = new ArrayList<>();
            for (ComponentClaim c : filterExcludedMentions) {
                SourceArtifact s = sourceById.get(c.sourceArtifactId);
                children.add(claimLeaf("RELATION_TEXT", "被排除的声明：" + s.type + " 中的 "
                        + c.name + "@" + display(c.version), c, s));
                String reason = filterExcluded.get(c.id);
                conditions.add("排除条件：" + reason);
                children.add(EvidenceNode.leaf("CONDITION", "排除条件：" + reason,
                        "CONDITION", null, null, null));
            }
            item.reasonDetail = "坐标存在声明，但被查询条件排除："
                    + filterExcludedMentions.stream().map(c -> filterExcluded.get(c.id)).distinct()
                    .collect(Collectors.joining("；"));
            item.conditions = writeJson(conditions);
            item.derivation = writeJson(EvidenceNode.all("ABSENCE", "被具体条件排除", children));
            item.counterevidence = writeJson(counter);
            return item;
        }

        // 2) 声明存在且未被筛选排除，但业务有效区间不覆盖观察时点 → 有效区间不覆盖
        if (!activeMentions.isEmpty()) {
            boolean anyValidAtB = activeMentions.stream()
                    .anyMatch(c -> claimInterval(c, sourceById.get(c.sourceArtifactId)).contains(b));
            if (!anyValidAtB) {
                item.reasonCode = ReplayItem.Reason.VALIDITY_MISMATCH.name();
                item.reasonDetail = "声明的业务有效区间不覆盖观察时点 " + b + "，该时点关系不成立";
                List<EvidenceNode> children = new ArrayList<>();
                for (ComponentClaim c : activeMentions) {
                    conditions.add("有效区间：" + c.name + "@" + display(c.version) + " "
                            + claimInterval(c, sourceById.get(c.sourceArtifactId)) + " 不含业务时点");
                    children.add(claimLeaf("RELATION_TEXT", "区间外声明：" + c.name + "@"
                            + display(c.version), c, sourceById.get(c.sourceArtifactId)));
                }
                item.conditions = writeJson(conditions);
                item.derivation = writeJson(EvidenceNode.all("ABSENCE", "有效区间不覆盖业务时点",
                        children));
                item.counterevidence = writeJson(counter);
                return item;
            }
        }

        // 3) 晚到来源已提到该坐标 → 来源未收到（不能据当前覆盖认定不存在）
        List<SourceArtifact> lateMention = sel.late.stream()
                .filter(s -> mentions(s, cq, mentions))
                .toList();
        if (!lateMention.isEmpty()) {
            item.reasonCode = ReplayItem.Reason.SOURCE_NOT_RECEIVED.name();
            item.reasonDetail = "资料截止时间前未收到相关来源；截止后到达的来源 "
                    + lateMention.stream().map(s -> s.type + "#" + s.id.substring(0, 8))
                    .collect(Collectors.joining(", "))
                    + " 提及该坐标，结论将随资料截止后移而改变";
            conditions.add("来源未收到：" + lateMention.stream()
                    .map(s -> s.type + "#" + s.id.substring(0, 8) + " 收集于 " + s.collectedAt)
                    .collect(Collectors.joining("; ")));
            item.conditions = writeJson(conditions);
            item.derivation = writeJson(EvidenceNode.all("ABSENCE", "来源未收到",
                    lateMention.stream().map(s -> EvidenceNode.leaf("SOURCE",
                            "晚到来源：" + s.type + "（收集于 " + s.collectedAt + "，晚于截止 " + k + "）",
                            "SOURCE", s.id, s.sourceUri, intervalOf(s).toString())).toList()));
            item.counterevidence = writeJson(counter);
            return item;
        }

        // 4) 已接收但未解析完整的文件提到该坐标 → 文件尚未解析完整
        List<SourceArtifact> unparsedMention = sel.unparsed.stream()
                .filter(s -> s.payload != null && cq.name() != null && s.payload.contains(cq.name()))
                .toList();
        if (!unparsedMention.isEmpty()) {
            item.reasonCode = ReplayItem.Reason.FILE_NOT_PARSED.name();
            item.reasonDetail = "相关文件已接收但尚未解析完整（"
                    + unparsedMention.stream().map(s -> s.type + "#" + s.id.substring(0, 8)
                    + " 状态=" + s.ingestStatus).collect(Collectors.joining(", "))
                    + "），无法据此确认或否定";
            conditions.add("解析状态：" + unparsedMention.stream()
                    .map(s -> s.type + "#" + s.id.substring(0, 8) + " " + s.ingestStatus)
                    .collect(Collectors.joining("; ")));
            item.conditions = writeJson(conditions);
            item.derivation = writeJson(EvidenceNode.all("ABSENCE", "文件尚未解析完整",
                    unparsedMention.stream().map(s -> EvidenceNode.leaf("SOURCE",
                            "未解析完整：" + s.type + " 状态=" + s.ingestStatus,
                            "SOURCE", s.id, s.sourceUri, intervalOf(s).toString())).toList()));
            item.counterevidence = writeJson(counter);
            return item;
        }

        // 5) 完整来源确实未包含 → 覆盖范围足以支持否定
        List<SourceArtifact> completeCovering = sel.usable.stream()
                .filter(s -> "COMPLETE".equals(s.completeness))
                .filter(s -> new Interval(s.validFrom, s.validTo).contains(b))
                .filter(s -> covers(s, cq))
                .toList();
        if (!completeCovering.isEmpty()) {
            item.itemType = "ABSENT";
            item.reasonCode = ReplayItem.Reason.NOT_IN_COMPLETE_SOURCE.name();
            item.reasonDetail = "完整清单来源确实未包含该坐标："
                    + completeCovering.stream().map(s -> s.type + "#" + s.id.substring(0, 8))
                    .collect(Collectors.joining(", "));
            List<EvidenceNode> children = new ArrayList<>();
            for (SourceArtifact s : completeCovering) {
                children.add(EvidenceNode.leaf("SOURCE",
                        "完整清单：" + s.type + "（" + display(s.completenessNote) + "）",
                        "SOURCE", s.id, s.sourceUri, intervalOf(s).toString()));
                conditions.add("来源完整性：" + s.type + "#" + s.id.substring(0, 8)
                        + " 声明 COMPLETE，范围 " + display(s.completenessNote));
            }
            conditions.add("否定判断：上述完整清单内无坐标 " + item.coordinate + " 的声明，"
                    + "覆盖范围足以支持该范围内不存在");
            item.conditions = writeJson(conditions);
            item.derivation = writeJson(EvidenceNode.all("ABSENCE", "完整来源确实未包含", children));
            item.counterevidence = writeJson(counter);
            return item;
        }

        // 6) 覆盖不足 → 未知，不能解释成没有
        item.reasonCode = ReplayItem.Reason.COVERAGE_INSUFFICIENT.name();
        List<String> gaps = new ArrayList<>();
        List<SourceArtifact> partial = sel.usable.stream()
                .filter(s -> !"COMPLETE".equals(s.completeness))
                .toList();
        for (SourceArtifact s : partial) {
            gaps.add(s.type + "#" + s.id.substring(0, 8) + "（"
                    + ("PARTIAL".equals(s.completeness) ? "残缺清单 " + display(s.completenessNote)
                    : "完整性未知") + "）");
        }
        item.reasonDetail = "资料覆盖范围不足以支持否定"
                + (gaps.isEmpty() ? "：无覆盖该坐标的完整来源" : "：" + String.join("；", gaps))
                + "，不能把未知解释成没有";
        conditions.add("覆盖判断：无 COMPLETE 且范围覆盖该坐标的来源，不能认定不存在");
        if (!gaps.isEmpty()) conditions.add("残缺/未知来源：" + String.join("；", gaps));
        item.conditions = writeJson(conditions);
        List<EvidenceNode> children = new ArrayList<>();
        for (SourceArtifact s : partial) {
            children.add(EvidenceNode.leaf("SOURCE",
                    "覆盖不足：" + s.type + "（" + display(s.completeness) + " "
                            + display(s.completenessNote) + "）",
                    "SOURCE", s.id, s.sourceUri, intervalOf(s).toString()));
        }
        if (children.isEmpty()) {
            children.add(EvidenceNode.leaf("CONDITION", "资料截止时间前未收到任何可否定该坐标的完整来源",
                    "CONDITION", null, null, null));
        }
        item.derivation = writeJson(EvidenceNode.all("ABSENCE", "覆盖范围不足，未知", children));
        item.counterevidence = writeJson(counter);
        return item;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 目录别名在 (B,K) 下的映射：别名 -> 目录名。后来确认的别名不用于解释此前的清单。 */
    public Map<String, String> aliasMapAt(Instant b, Instant k) {
        Map<String, String> map = new HashMap<>();
        for (CatalogEntry entry : entryRepo.findAll()) {
            List<CatalogRevision> revs = revisionRepo
                    .findByCatalogEntryIdOrderByRevisionDesc(entry.id).stream()
                    .sorted(Comparator.comparingInt(r -> r.revision)).toList();
            String aliases = entry.aliases;
            if (!revs.isEmpty()) {
                aliases = revs.get(0).oldValue != null
                        ? json.read(revs.get(0).oldValue).path("aliases").asText("[]") : "[]";
                for (CatalogRevision r : revs) {
                    if (r.createdAt.isAfter(k)) break;                       // 资料未收到
                    Instant eff = r.effectiveFrom != null ? r.effectiveFrom : r.createdAt;
                    if (eff.isAfter(b)) break;                               // 业务时点尚未生效
                    aliases = json.read(r.newValue).path("aliases").asText("[]");
                }
            }
            for (var node : json.read(aliases == null ? "[]" : aliases)) {
                map.put(node.asText(), entry.name);
            }
        }
        return map;
    }

    private boolean queryMatches(ComponentClaim c, CoordQuery cq, Map<String, String> aliasMap) {
        if (cq.name() == null || cq.name().isBlank()) return false;
        String name = cq.name();
        String canonical = aliasMap.getOrDefault(name, name);
        boolean nameOk = name.equals(c.name) || canonical.equals(c.name);
        if (!nameOk) return false;
        if (cq.version() != null && !cq.version().isBlank() && !cq.version().equals(c.version)) {
            return false;
        }
        if (cq.ecosystem() != null && !cq.ecosystem().isBlank()
                && !cq.ecosystem().equals(c.ecosystem == null || c.ecosystem.isBlank()
                ? "unknown" : c.ecosystem)) {
            return false;
        }
        return cq.namespace() == null || cq.namespace().isBlank()
                || cq.namespace().equals(c.namespace == null ? "" : c.namespace);
    }

    /** 查询坐标（含别名展开）是否命中已有输出条目的坐标键。 */
    private boolean queryMatchesKey(String itemKey, CoordQuery cq, Map<String, String> aliasMap) {
        if (cq.name() == null || cq.name().isBlank()) return false;
        String[] parts = itemKey.split("\\|", -1);
        String eco = parts.length > 0 ? parts[0] : "";
        String ns = parts.length > 1 ? parts[1] : "";
        String name = parts.length > 2 ? parts[2] : "";
        String version = parts.length > 3 ? parts[3] : "";
        String canonical = aliasMap.getOrDefault(cq.name(), cq.name());
        if (!cq.name().equals(name) && !canonical.equals(name)) return false;
        if (cq.version() != null && !cq.version().isBlank() && !cq.version().equals(version)) {
            return false;
        }
        if (cq.ecosystem() != null && !cq.ecosystem().isBlank() && !cq.ecosystem().equals(eco)) {
            return false;
        }
        return cq.namespace() == null || cq.namespace().isBlank() || cq.namespace().equals(ns);
    }

    private boolean mentions(SourceArtifact s, CoordQuery cq, List<ComponentClaim> mentions) {
        boolean claimHit = mentions.stream().anyMatch(c -> c.sourceArtifactId.equals(s.id));
        if (claimHit) return true;
        return s.payload != null && cq.name() != null && s.payload.contains(cq.name());
    }

    /** 来源完整范围是否覆盖该坐标（生态匹配即视为覆盖；范围说明仅作展示）。 */
    private boolean covers(SourceArtifact s, CoordQuery cq) {
        if (cq.ecosystem() == null || cq.ecosystem().isBlank()) return true;
        return s.ecosystem == null || s.ecosystem.isBlank() || cq.ecosystem().equals(s.ecosystem);
    }

    /** 查询条件筛选：否定筛选（scope 排除）与必填字段（空值判断）。 */
    private String filterExclusion(ComponentClaim c, ReplayQuery q) {
        String scope = c.scope == null || c.scope.isBlank() ? "runtime" : c.scope;
        if (q.excludeScopes() != null && q.excludeScopes().contains(scope)) {
            return "scope=" + scope + " 被查询条件 excludeScopes=" + q.excludeScopes() + " 排除";
        }
        if (q.requireFields() != null) {
            for (String f : q.requireFields()) {
                String v = switch (f) {
                    case "publisher" -> c.publisher;
                    case "version" -> c.version;
                    case "hashValue" -> c.hashValue;
                    case "namespace" -> c.namespace;
                    case "ecosystem" -> c.ecosystem;
                    default -> "n/a";
                };
                if (v == null || v.isBlank()) {
                    return "必填字段 " + f + " 为空，不满足查询条件 requireFields=" + q.requireFields();
                }
            }
        }
        return null;
    }

    private EvidenceNode relationAnyNode(Map<String, List<ComponentClaim>> byOrigin,
                                         Map<String, SourceArtifact> sourceById) {
        List<EvidenceNode> nodes = new ArrayList<>();
        for (Map.Entry<String, List<ComponentClaim>> e : byOrigin.entrySet()) {
            SourceArtifact rep = sourceById.get(e.getValue().get(0).sourceArtifactId);
            for (ComponentClaim c : e.getValue()) {
                nodes.add(claimLeaf("RELATION_TEXT",
                        "关系原文：" + rep.type + " 声明" + copyNote(e.getValue()), c, rep));
            }
        }
        if (nodes.isEmpty()) {
            nodes.add(EvidenceNode.leaf("CONDITION", "无实际解析证据的关系声明", "CONDITION",
                    null, null, null));
        }
        return EvidenceNode.any("RELATION_TEXT", "关系声明（可相互替代）", false, nodes);
    }

    private EvidenceNode identityAnyNode(SubIdentity sub, Map<String, SourceArtifact> sourceById) {
        List<EvidenceNode> nodes = new ArrayList<>();
        for (ComponentClaim c : sub.confirmClaims) {
            SourceArtifact s = sourceById.get(c.sourceArtifactId);
            nodes.add(claimLeaf("IDENTITY", "包身份确认：解析证据哈希一致（" + s.type + "）", c, s));
        }
        if (sub.confirmSignature != null) {
            nodes.add(EvidenceNode.leaf("IDENTITY",
                    "包身份确认：有效签名 signer=" + display(sub.confirmSignature.signer),
                    "SIGNATURE", sub.confirmSignature.id, null,
                    sub.confirmInterval.toString()));
        }
        if (nodes.isEmpty()) {
            nodes.add(EvidenceNode.leaf("CONDITION", "无身份确认依据", "CONDITION",
                    null, null, null));
        }
        return EvidenceNode.any("IDENTITY", "包身份确认依据（可相互替代）", false, nodes);
    }

    private List<Map<String, Object>> collectCounterevidence(SubIdentity sub) {
        List<Map<String, Object>> counter = new ArrayList<>();
        if (sub.hashValue != null) {
            signatureRepo.findBySubjectHash(sub.hashValue).stream()
                    .filter(s -> s.revokedAt != null)
                    .forEach(s -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("kind", "REVOKED_SIGNATURE");
                        m.put("detail", "签名 " + s.id.substring(0, 8) + " 已于 " + s.revokedAt
                                + " 撤销，未作为确认依据");
                        m.put("refId", s.id);
                        counter.add(m);
                    });
        }
        return counter;
    }

    private String copyNote(List<ComponentClaim> claims) {
        Set<String> files = claims.stream().map(c -> c.sourceArtifactId).collect(Collectors.toSet());
        if (files.size() <= 1) return "";
        return "（同一原始记录的 " + files.size() + " 份转抄文件计为 1 份独立支持）";
    }

    private EvidenceNode claimLeaf(String role, String label, ComponentClaim c, SourceArtifact s) {
        return EvidenceNode.leaf(role, label, "CLAIM", c.id,
                (s == null ? "" : nullToEmpty(s.sourceUri)) + "#claim:" + c.id.substring(0, 8),
                claimInterval(c, s).toString());
    }

    private ReplayItem newItem(ReplayTask task, String groupKey, String type) {
        ReplayItem item = new ReplayItem();
        item.replayTaskId = task.id;
        item.itemType = type;
        item.coordKey = groupKey;
        item.coordinate = coordinateOf(groupKey);
        return item;
    }

    public static String coordKey(String ecosystem, String namespace, String name, String version) {
        return String.join("|",
                ecosystem == null || ecosystem.isBlank() ? "unknown" : ecosystem,
                namespace == null ? "" : namespace,
                name == null ? "" : name,
                version == null ? "" : version);
    }

    public static String coordinateOf(String coordKey) {
        String[] parts = coordKey.split("\\|", -1);
        String eco = parts.length > 0 ? parts[0] : "unknown";
        String ns = parts.length > 1 ? parts[1] : "";
        String name = parts.length > 2 ? parts[2] : "";
        String version = parts.length > 3 ? parts[3] : "";
        return eco + ":" + (ns.isBlank() ? "" : ns + "/") + name
                + (version.isBlank() ? "" : "@" + version);
    }

    private Interval intervalOf(SourceArtifact s) {
        return new Interval(s.validFrom, s.validTo);
    }

    private String writeJson(Object o) {
        return json.write(o);
    }

    private static String display(String s) {
        return s == null || s.isBlank() ? "∅" : s;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
