package com.scf.meta.source;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 来源保全册条目：保存 SBOM、锁文件、构建日志、包仓响应、源码扫描、
 * 签名索引、组件目录、漏洞通告的原始载荷与接收元数据。追加式保存，不可改写。
 */
@Entity
@Table(name = "source_artifact")
public class SourceArtifact {

    public enum Type {
        SBOM, LOCKFILE, BUILD_LOG, REPO_METADATA, SOURCE_SCAN,
        SIGNATURE_INDEX, COMPONENT_CATALOG, VULN_ADVISORY
    }

    public enum IngestStatus { RECEIVED, PARSED, PARSED_WITH_WARNINGS, REJECTED }

    @Id
    public String id;

    @Column(nullable = false, length = 32)
    public String type;

    /** 来源地址（仓库 URL、构建系统地址、镜像地址等） */
    @Column(length = 1024)
    public String sourceUri;

    @Column(length = 32)
    public String ecosystem;

    /** 关联构建标识 */
    @Column(length = 128)
    public String buildId;

    /** 原始载荷，原样保存 */
    @Column(nullable = false, columnDefinition = "text")
    public String payload;

    /** 载荷 SHA-256 */
    @Column(nullable = false, length = 64)
    public String payloadHash;

    @Column(length = 64)
    public String parserVersion;

    @Column(nullable = false, length = 24)
    public String ingestStatus;

    /** 来源声称的事件时间 */
    public Instant eventTime;

    /** 平台接收时间 */
    @Column(nullable = false)
    public Instant collectedAt;

    /** 解析警告、待核项说明等 */
    @Column(columnDefinition = "text")
    public String note;

    /** 修订链：本来源更正/替代的另一来源 id（同一来源的更正版） */
    public String revisesId;

    /** 原始记录标识：同一原始记录被复制/转抄到多个文件时共享该标识，去重后只算一份独立支持 */
    @Column(length = 128)
    public String originId;

    /** 清单完整范围：COMPLETE（完整清单）/ PARTIAL（残缺清单）/ UNKNOWN（未知） */
    @Column(length = 16)
    public String completeness;

    /** 清单覆盖范围说明（如“覆盖 runtime 全部直接依赖”） */
    @Column(length = 512)
    public String completenessNote;

    /** 声明关系的业务有效起点（空表示不限） */
    public Instant validFrom;

    /** 声明关系的业务有效终点（空表示不限） */
    public Instant validTo;

    @Column(nullable = false)
    public Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = Instant.now();
        if (collectedAt == null) collectedAt = Instant.now();
    }
}
