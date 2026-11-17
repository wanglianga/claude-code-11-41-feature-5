package com.scf.meta.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** 目录修订记录：别名或发布者归属的每次变化。 */
@Entity
@Table(name = "catalog_revision")
public class CatalogRevision {

    @Id
    public String id;

    @Column(nullable = false)
    public String catalogEntryId;

    @Column(nullable = false)
    public int revision;

    @Column(length = 512)
    public String changeSummary;

    @Column(columnDefinition = "text")
    public String oldValue;

    @Column(columnDefinition = "text")
    public String newValue;

    @Column(nullable = false)
    public Instant createdAt;

    /** 修订的业务生效时间（追溯修订可早于登记时间；空表示自登记时起生效） */
    public Instant effectiveFrom;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = Instant.now();
    }
}
