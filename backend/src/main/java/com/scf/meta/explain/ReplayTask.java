package com.scf.meta.explain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 复演任务：固定业务时点、资料截止时间、规则版本与查询条件后的一次时点复演。
 * 任务与其结果条目持久保存，原先签发的结果保持可回查；
 * 同一业务时点采用不同资料截止时间可得到不同结论。
 */
@Entity
@Table(name = "replay_task")
public class ReplayTask {

    @Id
    public String id;

    @Column(nullable = false, length = 128)
    public String buildId;

    /** 业务时点：观察哪次构建在何时的事实 */
    @Column(nullable = false)
    public Instant businessTime;

    /** 资料截止时间：当时已收到哪些依据 */
    @Column(nullable = false)
    public Instant knowledgeCutoff;

    @Column(nullable = false, length = 64)
    public String ruleVersion;

    /** 查询条件（JSON）：缺席对象坐标、否定筛选、必填字段 */
    @Column(columnDefinition = "text")
    public String queryJson;

    @Column(columnDefinition = "text")
    public String summary;

    @Column(length = 64)
    public String createdBy;

    @Column(nullable = false)
    public Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = Instant.now();
    }
}
