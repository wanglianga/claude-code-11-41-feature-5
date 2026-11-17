package com.scf.meta.catalog;

import com.scf.meta.buildfact.BuildFact;
import com.scf.meta.buildfact.BuildFactItem;
import com.scf.meta.buildfact.BuildFactItemRepository;
import com.scf.meta.buildfact.BuildFactRepository;
import com.scf.meta.common.Json;
import com.scf.meta.identity.ComponentIdentity;
import com.scf.meta.identity.ComponentIdentityRepository;
import com.scf.meta.monitor.MonitorEvent;
import com.scf.meta.monitor.MonitorService;
import com.scf.meta.review.ReviewService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 组件目录与消费目录服务。 */
@Service
public class CatalogService {

    private final CatalogEntryRepository entryRepo;
    private final CatalogRevisionRepository revisionRepo;
    private final ConsumerViewRepository viewRepo;
    private final BuildFactRepository factRepo;
    private final BuildFactItemRepository itemRepo;
    private final ComponentIdentityRepository identityRepo;
    private final MonitorService monitorService;
    private final ReviewService reviewService;
    private final Json json;

    public CatalogService(CatalogEntryRepository entryRepo,
                          CatalogRevisionRepository revisionRepo,
                          ConsumerViewRepository viewRepo,
                          BuildFactRepository factRepo,
                          BuildFactItemRepository itemRepo,
                          ComponentIdentityRepository identityRepo,
                          MonitorService monitorService,
                          ReviewService reviewService,
                          Json json) {
        this.entryRepo = entryRepo;
        this.revisionRepo = revisionRepo;
        this.viewRepo = viewRepo;
        this.factRepo = factRepo;
        this.itemRepo = itemRepo;
        this.identityRepo = identityRepo;
        this.monitorService = monitorService;
        this.reviewService = reviewService;
        this.json = json;
    }

    @Transactional
    public CatalogEntry createEntry(String ecosystem, String namespace, String name,
                                    String publisher, String aliases, String actor) {
        CatalogEntry e = new CatalogEntry();
        e.ecosystem = ecosystem == null ? "unknown" : ecosystem;
        e.namespace = namespace == null ? "" : namespace;
        e.name = name;
        e.publisher = publisher;
        e.aliases = aliases == null ? "[]" : aliases;
        entryRepo.save(e);
        reviewService.record("VIEW", e.id, "CATALOG_ENTRY_CREATED",
                e.ecosystem + ":" + e.name + " publisher=" + publisher, actor);
        return e;
    }

    /** 目录修订：改变别名或发布者归属，触发变动监测。effectiveFrom 为业务生效时间（可追溯）。 */
    @Transactional
    public CatalogRevision reviseEntry(String entryId, String newPublisher, String newAliases,
                                       String summary, String actor) {
        return reviseEntry(entryId, newPublisher, newAliases, summary, null, actor);
    }

    @Transactional
    public CatalogRevision reviseEntry(String entryId, String newPublisher, String newAliases,
                                       String summary, java.time.Instant effectiveFrom, String actor) {
        CatalogEntry entry = entryRepo.findById(entryId)
                .orElseThrow(() -> new IllegalArgumentException("目录条目不存在: " + entryId));
        Map<String, Object> oldValue = new LinkedHashMap<>();
        oldValue.put("publisher", entry.publisher);
        oldValue.put("aliases", entry.aliases);
        oldValue.put("revision", entry.revision);

        entry.revision++;
        if (newPublisher != null) entry.publisher = newPublisher;
        if (newAliases != null) entry.aliases = newAliases;
        entry.updatedAt = java.time.Instant.now();
        entryRepo.save(entry);

        Map<String, Object> newValue = new LinkedHashMap<>();
        newValue.put("publisher", entry.publisher);
        newValue.put("aliases", entry.aliases);
        newValue.put("revision", entry.revision);

        CatalogRevision revision = new CatalogRevision();
        revision.catalogEntryId = entry.id;
        revision.revision = entry.revision;
        revision.changeSummary = summary;
        revision.oldValue = json.write(oldValue);
        revision.newValue = json.write(newValue);
        revision.effectiveFrom = effectiveFrom;
        revisionRepo.save(revision);

        // 变动监测：定位当前引用该坐标的身份与下游视图并暂停
        List<ComponentIdentity> identities = identityRepo.findByEcosystemAndNamespaceAndName(
                entry.ecosystem, entry.namespace, entry.name);
        monitorService.suspendViewsReferencing(identities,
                "目录条目 " + entry.ecosystem + ":" + entry.name + " 第 " + entry.revision
                        + " 次修订（" + (summary == null ? "别名/发布者归属变更" : summary) + "）",
                MonitorEvent.Type.CATALOG_REVISION.name(), actor);
        reviewService.record("VIEW", entry.id, "CATALOG_REVISED",
                "revision=" + entry.revision + " " + summary, actor);
        return revision;
    }

    /** 创建下游视图，冻结当时各身份版本快照。 */
    @Transactional
    public ConsumerView createView(String viewType, String name, String buildFactId, String actor) {
        BuildFact fact = factRepo.findById(buildFactId)
                .orElseThrow(() -> new IllegalArgumentException("构建事实不存在: " + buildFactId));
        try {
            ConsumerView.Type.valueOf(viewType);
        } catch (Exception e) {
            throw new IllegalArgumentException("未知视图类型: " + viewType);
        }
        Map<String, Integer> identityVersions = new LinkedHashMap<>();
        for (BuildFactItem item : itemRepo.findByBuildFactId(fact.id)) {
            if ("IDENTITY".equals(item.itemKind)) {
                var node = json.read(item.snapshot);
                identityVersions.put(item.refId, node.get("versionNo").asInt());
            }
        }
        ConsumerView view = new ConsumerView();
        view.viewType = viewType;
        view.name = name;
        view.buildFactId = fact.id;
        view.identityVersions = json.write(identityVersions);
        viewRepo.save(view);
        reviewService.record("VIEW", view.id, "CREATED",
                viewType + " 引用事实 " + fact.buildId + " v" + fact.factVersion, actor);
        return view;
    }
}
