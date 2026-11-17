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
import com.scf.meta.signature.SignatureRecord;
import com.scf.meta.signature.SignatureRecordRepository;
import com.scf.meta.source.SourceArtifact;
import com.scf.meta.source.SourceArtifactRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 解释服务：在完整推导图上求不可再删减的充分证据组合（精简只裁剪展示依据，
 * 不改变计算输入，也不隐藏反证与互斥条件）；同一业务时点切换资料截止时间
 * 并排对比并归因到补证/修订；导出内容可用相同输入重新计算。
 */
@Service
public class ExplainService {

    private final ReplayTaskRepository taskRepo;
    private final ReplayItemRepository itemRepo;
    private final ReplayService replayService;
    private final SourceArtifactRepository sourceRepo;
    private final ComponentClaimRepository claimRepo;
    private final ComponentIdentityRepository identityRepo;
    private final SignatureRecordRepository signatureRepo;
    private final CatalogEntryRepository entryRepo;
    private final CatalogRevisionRepository revisionRepo;
    private final Json json;

    public ExplainService(ReplayTaskRepository taskRepo, ReplayItemRepository itemRepo,
                          ReplayService replayService, SourceArtifactRepository sourceRepo,
                          ComponentClaimRepository claimRepo,
                          ComponentIdentityRepository identityRepo,
                          SignatureRecordRepository signatureRepo,
                          CatalogEntryRepository entryRepo,
                          CatalogRevisionRepository revisionRepo, Json json) {
        this.taskRepo = taskRepo;
        this.itemRepo = itemRepo;
        this.replayService = replayService;
        this.sourceRepo = sourceRepo;
        this.claimRepo = claimRepo;
        this.identityRepo = identityRepo;
        this.signatureRepo = signatureRepo;
        this.entryRepo = entryRepo;
        this.revisionRepo = revisionRepo;
        this.json = json;
    }

    // ------------------------------------------------------------------
    // 单条记录的解释
    // ------------------------------------------------------------------

    public Map<String, Object> explain(String itemId, Integer budget) {
        ReplayItem item = itemRepo.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("复演条目不存在: " + itemId));
        ReplayTask task = taskRepo.findById(item.replayTaskId)
                .orElseThrow(() -> new IllegalStateException("复演任务缺失: " + item.replayTaskId));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("item", item);
        result.put("task", task);
        result.put("derivation", json.read(item.derivation == null ? "{}" : item.derivation));
        result.put("conditions", item.conditions == null ? List.of()
                : json.mapper().readValue(item.conditions, List.class));
        result.put("counterevidence", item.counterevidence == null ? List.of()
                : json.mapper().readValue(item.counterevidence, List.class));
        result.put("applicableSources", applicableSources(task));
        result.put("minimal", minimalCombinations(item, budget == null ? 10000 : budget));
        result.put("notes", List.of(
                "精简组合只裁剪展示依据，不改变计算输入；不可再删减不代表全局最短或唯一解释",
                "反证与互斥条件不在精简中隐藏，随解释一并展示",
                "搜索达到预算时返回已核实组合与未检查分支，不声称穷尽；未找到组合也不否定已算出的记录"));
        return result;
    }

    /** 适用资料：全部来源及其在该复演时点下的角色。 */
    private List<Map<String, Object>> applicableSources(ReplayTask task) {
        List<SourceArtifact> all = sourceRepo.findByBuildIdOrderByCollectedAtAsc(task.buildId);
        ReplayService.SourceSelection sel = replayService.selectSources(
                all, task.businessTime, task.knowledgeCutoff);
        Set<String> usable = new LinkedHashSet<>();
        sel.usable.forEach(s -> usable.add(s.id));
        Set<String> unparsed = new LinkedHashSet<>();
        sel.unparsed.forEach(s -> unparsed.add(s.id));
        List<Map<String, Object>> out = new ArrayList<>();
        for (SourceArtifact s : all) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id);
            m.put("type", s.type);
            m.put("sourceUri", s.sourceUri);
            m.put("collectedAt", s.collectedAt.toString());
            m.put("payloadHash", s.payloadHash);
            m.put("completeness", s.completeness);
            m.put("completenessNote", s.completenessNote);
            m.put("originId", s.originId);
            m.put("revisesId", s.revisesId);
            String role;
            if (usable.contains(s.id)) {
                role = unparsed.contains(s.id) ? "可用-未解析完整" : "可用";
            } else if (sel.supersededBy.containsKey(s.id)) {
                role = "被取代（由 " + shortId(sel.supersededBy.get(s.id)) + " 适用）";
            } else if (sel.notYetEffective.stream().anyMatch(x -> x.id.equals(s.id))) {
                role = "业务时点尚未生效";
            } else {
                role = "资料截止后到达";
            }
            m.put("role", role);
            out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 精简组合：不可再删减的充分证据组合
    // ------------------------------------------------------------------

    /** 搜索预算：ANY 替代分支的展开次数上限，达到即停止并如实报告覆盖范围。 */
    private static class SearchState {
        final int budget;
        int expansions;
        boolean stop;
        final List<String> unchecked = new ArrayList<>();

        SearchState(int budget) {
            this.budget = Math.max(0, budget);
        }

        boolean overBudget() {
            return expansions >= budget;
        }
    }

    public Map<String, Object> minimalCombinations(ReplayItem item, int budget) {
        EvidenceNode root = json.mapper().readValue(
                item.derivation == null ? "{}" : item.derivation, EvidenceNode.class);
        Map<String, EvidenceNode> leaves = new LinkedHashMap<>();
        assignIds(root, leaves, new int[]{0});

        SearchState st = new SearchState(budget);
        List<Set<String>> raw = root == null || root.type == null ? List.of() : enumerate(root, st);

        // 逐组收缩到不可再删减，再去重并核实
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> combos = new ArrayList<>();
        for (Set<String> candidate : raw) {
            Set<String> shrunk = shrink(root, candidate);
            if (shrunk.isEmpty()) continue;
            String key = String.join(",", new ArrayList<>(shrunk).stream().sorted().toList());
            if (!seen.add(key)) continue;
            if (!verify(root, shrunk)) continue;      // 核实：成立、互斥一致且不可再删减
            Map<String, Object> combo = new LinkedHashMap<>();
            combo.put("size", shrunk.size());
            List<Map<String, Object>> leafList = new ArrayList<>();
            for (String leafId : shrunk) {
                EvidenceNode leaf = leaves.get(leafId);
                Map<String, Object> lm = new LinkedHashMap<>();
                lm.put("id", leafId);
                lm.put("label", leaf.label);
                lm.put("role", leaf.role);
                lm.put("refType", leaf.refType);
                lm.put("refId", leaf.refId);
                lm.put("location", leaf.location);
                lm.put("interval", leaf.interval);
                leafList.add(lm);
            }
            combo.put("leaves", leafList);
            combo.put("verified", true);
            combos.add(combo);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("combinations", combos);
        result.put("verifiedCount", combos.size());
        result.put("exhaustive", !st.stop);
        result.put("uncheckedBranches", st.unchecked);
        if (st.stop) {
            result.put("coverageNote", "搜索达到预算：已核实 " + combos.size()
                    + " 组精简组合，" + st.unchecked.size() + " 条替代分支尚未检查，未穷尽；"
                    + "未找到组合也不否定已算出的记录");
        } else if (combos.isEmpty()) {
            result.put("coverageNote", "未找到精简组合（可能预算为 0 或推导图无叶证据），"
                    + "不据此否定已算出的记录");
        } else {
            result.put("coverageNote", "已穷尽全部替代分支，核实 " + combos.size()
                    + " 组不可再删减的充分证据组合");
        }
        return result;
    }

    private void assignIds(EvidenceNode node, Map<String, EvidenceNode> leaves, int[] counter) {
        if (node == null) return;
        if ("LEAF".equals(node.type)) {
            node.id = "L" + (++counter[0]);
            leaves.put(node.id, node);
            return;
        }
        if (node.children != null) {
            for (EvidenceNode child : node.children) assignIds(child, leaves, counter);
        }
    }

    private List<Set<String>> enumerate(EvidenceNode node, SearchState st) {
        if (node == null || node.type == null) return List.of();
        switch (node.type) {
            case "LEAF" -> {
                return List.of(Set.of(node.id));
            }
            case "ANY" -> {
                List<Set<String>> out = new ArrayList<>();
                for (EvidenceNode child : node.children == null ? List.<EvidenceNode>of()
                        : node.children) {
                    st.expansions++;
                    if (st.overBudget()) {
                        st.stop = true;
                        st.unchecked.add(branchLabel(node, child));
                        continue;
                    }
                    out.addAll(enumerate(child, st));
                }
                return out;
            }
            case "ALL" -> {
                List<Set<String>> acc = new ArrayList<>();
                acc.add(new LinkedHashSet<>());
                for (EvidenceNode child : node.children == null ? List.<EvidenceNode>of()
                        : node.children) {
                    List<Set<String>> childSets = enumerate(child, st);
                    List<Set<String>> next = new ArrayList<>();
                    for (Set<String> a : acc) {
                        for (Set<String> c : childSets) {
                            Set<String> union = new LinkedHashSet<>(a);
                            union.addAll(c);
                            if (consistent(union, node)) next.add(union);
                        }
                    }
                    acc = next;
                }
                return acc;
            }
            default -> {
                return List.of();
            }
        }
    }

    private String branchLabel(EvidenceNode parent, EvidenceNode child) {
        return (parent.label == null ? parent.role : parent.label) + " > "
                + (child.label == null ? child.role : child.label);
    }

    /** 互斥一致：同一互斥组下不得合并不同候选的证据。 */
    private boolean consistent(Set<String> set, EvidenceNode scope) {
        Map<String, String> chosen = new LinkedHashMap<>();
        for (String leafId : set) {
            EvidenceNode leaf = findLeaf(scope, leafId);
            if (leaf == null || leaf.assumptions == null) continue;
            for (String assumption : leaf.assumptions) {
                int sep = assumption.lastIndexOf("->");
                String group = sep > 0 ? assumption.substring(0, sep) : assumption;
                String cand = sep > 0 ? assumption.substring(sep + 2) : "";
                String prev = chosen.putIfAbsent(group, cand);
                if (prev != null && !prev.equals(cand)) return false;
            }
        }
        return true;
    }

    private EvidenceNode findLeaf(EvidenceNode node, String id) {
        if (node == null) return null;
        if (Objects.equals(node.id, id)) return node;
        if (node.children != null) {
            for (EvidenceNode child : node.children) {
                EvidenceNode found = findLeaf(child, id);
                if (found != null) return found;
            }
        }
        return null;
    }

    private boolean evaluate(EvidenceNode node, Set<String> available) {
        if (node == null || node.type == null) return false;
        return switch (node.type) {
            case "LEAF" -> available.contains(node.id);
            case "ANY" -> node.children != null && node.children.stream()
                    .anyMatch(c -> evaluate(c, available));
            case "ALL" -> node.children != null && !node.children.isEmpty()
                    && node.children.stream().allMatch(c -> evaluate(c, available));
            default -> false;
        };
    }

    /** 贪心收缩：能删则删，直到任何叶子再删都会破坏成立性。 */
    private Set<String> shrink(EvidenceNode root, Set<String> candidate) {
        Set<String> s = new LinkedHashSet<>(candidate);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String leaf : new ArrayList<>(s)) {
                Set<String> t = new LinkedHashSet<>(s);
                t.remove(leaf);
                if (!t.isEmpty() && evaluate(root, t) && consistent(t, root)) {
                    s = t;
                    changed = true;
                }
            }
        }
        return s;
    }

    /** 核实：组合成立、互斥一致，且每片叶子都不可再删。 */
    private boolean verify(EvidenceNode root, Set<String> combo) {
        if (!evaluate(root, combo) || !consistent(combo, root)) return false;
        for (String leaf : combo) {
            Set<String> t = new LinkedHashSet<>(combo);
            t.remove(leaf);
            if (evaluate(root, t)) return false;   // 仍可成立 → 不是不可再删减
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 双时点对比：同一业务时点，不同资料截止时间
    // ------------------------------------------------------------------

    public Map<String, Object> compare(String taskId, Instant otherCutoff, String actor) {
        ReplayTask t1 = taskRepo.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("复演任务不存在: " + taskId));
        ReplayService.ReplayQuery q;
        try {
            q = json.mapper().readValue(t1.queryJson, ReplayService.ReplayQuery.class);
        } catch (Exception e) {
            q = ReplayService.ReplayQuery.empty();
        }
        ReplayTask t2 = replayService.replay(t1.buildId, t1.businessTime, otherCutoff, q, actor);

        List<ReplayItem> items1 = itemRepo.findByReplayTaskId(t1.id);
        List<ReplayItem> items2 = itemRepo.findByReplayTaskId(t2.id);
        Map<String, List<ReplayItem>> byKey1 = groupByKey(items1);
        Map<String, List<ReplayItem>> byKey2 = groupByKey(items2);
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(byKey1.keySet());
        keys.addAll(byKey2.keySet());

        List<Map<String, Object>> changes = new ArrayList<>();
        int unchanged = 0;
        for (String key : keys) {
            String s1 = summarize(byKey1.get(key));
            String s2 = summarize(byKey2.get(key));
            if (!Objects.equals(s1, s2)) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("coordKey", key);
                change.put("coordinate", ReplayService.coordinateOf(key));
                change.put("before", s1 == null ? "（无记录）" : s1);
                change.put("after", s2 == null ? "（无记录）" : s2);
                change.put("attributions", attribute(key, t1.buildId,
                        t1.knowledgeCutoff, t2.knowledgeCutoff));
                changes.add(change);
            } else {
                unchanged++;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task1Id", t1.id);
        result.put("task2Id", t2.id);
        result.put("businessTime", t1.businessTime.toString());
        result.put("cutoff1", t1.knowledgeCutoff.toString());
        result.put("cutoff2", t2.knowledgeCutoff.toString());
        result.put("changes", changes);
        result.put("unchanged", unchanged);
        result.put("note", "同一业务时点采用不同资料截止时间得到不同结论时，"
                + "变化归因到具体补证或修订；两次复演结果均已保存，可回查");
        return result;
    }

    private Map<String, List<ReplayItem>> groupByKey(List<ReplayItem> items) {
        Map<String, List<ReplayItem>> map = new LinkedHashMap<>();
        for (ReplayItem i : items) map.computeIfAbsent(i.coordKey, k -> new ArrayList<>()).add(i);
        return map;
    }

    private String summarize(List<ReplayItem> items) {
        if (items == null || items.isEmpty()) return null;
        return items.stream()
                .map(i -> i.itemType + (i.reasonCode == null ? "" : "/" + i.reasonCode)
                        + (i.identityId == null ? "" : "#" + i.identityId.substring(0, 8)))
                .sorted().reduce((a, b) -> a + ";" + b).orElse(null);
    }

    /** 变化归因：窗口内到达的补证来源、目录修订与签名撤销。 */
    private List<Map<String, Object>> attribute(String coordKey, String buildId,
                                                Instant k1, Instant k2) {
        Instant from = k1.isBefore(k2) ? k1 : k2;
        Instant to = k1.isBefore(k2) ? k2 : k1;
        String[] parts = coordKey.split("\\|", -1);
        String name = parts.length > 2 ? parts[2] : "";
        List<Map<String, Object>> out = new ArrayList<>();

        for (SourceArtifact s : sourceRepo.findByBuildIdOrderByCollectedAtAsc(buildId)) {
            if (s.collectedAt.isAfter(from) && !s.collectedAt.isAfter(to)
                    && s.payload != null && s.payload.contains(name)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("kind", "SOURCE");
                m.put("id", s.id);
                m.put("detail", "补证来源 " + s.type + "#" + s.id.substring(0, 8)
                        + " 收集于 " + s.collectedAt + "，载荷提及 " + name);
                out.add(m);
            }
        }
        for (CatalogEntry entry : entryRepo.findAll()) {
            boolean nameHit = entry.name.equals(name)
                    || (entry.aliases != null && entry.aliases.contains(name));
            if (!nameHit) continue;
            for (CatalogRevision r : revisionRepo.findByCatalogEntryIdOrderByRevisionDesc(entry.id)) {
                if (r.createdAt.isAfter(from) && !r.createdAt.isAfter(to)) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("kind", "CATALOG_REVISION");
                    m.put("id", r.id);
                    m.put("detail", "目录修订 " + entry.name + " rev" + r.revision + "（"
                            + r.changeSummary + "）登记于 " + r.createdAt
                            + (r.effectiveFrom != null ? "，生效于 " + r.effectiveFrom : ""));
                    out.add(m);
                }
            }
        }
        for (ComponentIdentity identity : identityRepo.findByName(name)) {
            if (identity.hashValue == null) continue;
            for (SignatureRecord sig : signatureRepo.findBySubjectHash(identity.hashValue)) {
                if (sig.revokedAt != null && sig.revokedAt.isAfter(from)
                        && !sig.revokedAt.isAfter(to)) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("kind", "SIGNATURE_REVOCATION");
                    m.put("id", sig.id);
                    m.put("detail", "签名 " + sig.id.substring(0, 8) + " 于 " + sig.revokedAt
                            + " 撤销，影响 " + name + " 的身份确认");
                    out.add(m);
                }
            }
        }
        if (out.isEmpty()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", "NONE");
            m.put("detail", "窗口内未定位到直接来源事件，变化可能来自修订链适用或筛选条件");
            out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 导出：可用相同输入重新计算
    // ------------------------------------------------------------------

    public Map<String, Object> export(String taskId) {
        ReplayTask task = taskRepo.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("复演任务不存在: " + taskId));
        List<ReplayItem> items = itemRepo.findByReplayTaskId(taskId);
        items.sort(Comparator.comparing(i -> i.coordKey));

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("buildId", task.buildId);
        inputs.put("businessTime", task.businessTime.toString());
        inputs.put("knowledgeCutoff", task.knowledgeCutoff.toString());
        inputs.put("ruleVersion", task.ruleVersion);
        inputs.put("query", json.read(task.queryJson));

        List<Map<String, Object>> itemList = new ArrayList<>();
        for (ReplayItem i : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("coordKey", i.coordKey);
            m.put("coordinate", i.coordinate);
            m.put("itemType", i.itemType);
            m.put("reasonCode", i.reasonCode);
            m.put("reasonDetail", i.reasonDetail);
            m.put("conditions", i.conditions == null ? List.of()
                    : json.mapper().readValue(i.conditions, List.class));
            m.put("derivation", json.read(i.derivation == null ? "{}" : i.derivation));
            m.put("counterevidence", i.counterevidence == null ? List.of()
                    : json.mapper().readValue(i.counterevidence, List.class));
            itemList.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exportVersion", 1);
        result.put("generatedAt", Instant.now().toString());
        result.put("note", "使用 inputs 作为 POST /api/explain/replay 的请求体可用相同输入重新计算，"
                + "结论由两个时点与规则版本决定，不受后续到达资料影响");
        result.put("inputs", inputs);
        result.put("items", itemList);
        return result;
    }

    private static String shortId(String id) {
        return id == null ? "?" : id.substring(0, Math.min(8, id.length()));
    }
}
