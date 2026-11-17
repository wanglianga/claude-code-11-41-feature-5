package com.scf.meta.explain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 复演输出条目：一次复演在某个坐标上的结论。
 * PRESENT 出现（已证实） / ABSENT 缺席（覆盖范围足以支持否定，或被具体条件排除） /
 * UNKNOWN 未知（覆盖不足，不能解释成没有） / CONFLICT 冲突（互斥身份候选并存）。
 * 解释结果关联输出记录或明确指定的缺席对象。
 */
@Entity
@Table(name = "replay_item")
public class ReplayItem {

    public enum Type { PRESENT, ABSENT, UNKNOWN, CONFLICT }

    /** 缺席/未知原因分类 */
    public enum Reason {
        PROVEN,                         // 已证实出现
        NOT_IN_COMPLETE_SOURCE,         // 完整来源确实未包含
        EXCLUDED_BY_CONDITION,          // 被具体条件排除（否定筛选/空值判断）
        SOURCE_NOT_RECEIVED,            // 来源未收到
        FILE_NOT_PARSED,                // 文件尚未解析完整
        IDENTITY_UNDECIDED,             // 身份未决
        VALIDITY_MISMATCH,              // 有效区间无交集，链路从未成立
        COVERAGE_INSUFFICIENT,          // 覆盖范围不足以支持否定
        MUTUALLY_EXCLUSIVE_IDENTITIES   // 互斥身份候选
    }

    @Id
    public String id;

    @Column(nullable = false)
    public String replayTaskId;

    @Column(nullable = false, length = 16)
    public String itemType;

    /** 坐标归一键：ecosystem|namespace|name|version（查询与对比按此对齐） */
    @Column(nullable = false, length = 1408)
    public String coordKey;

    /** 坐标展示文本 */
    @Column(nullable = false, length = 1408)
    public String coordinate;

    /** 关联身份（冲突条目为空，候选身份在推导图中分别保留） */
    public String identityId;

    @Column(length = 40)
    public String reasonCode;

    @Column(columnDefinition = "text")
    public String reasonDetail;

    /** 条件判断（JSON 数组）：空值、否定筛选、来源完整性、有效区间等判断条件 */
    @Column(columnDefinition = "text")
    public String conditions;

    /** 完整推导图（JSON）：AND 共同必需 / OR 可相互替代，可展开到原始位置 */
    @Column(columnDefinition = "text")
    public String derivation;

    /** 反证与互斥条件（JSON 数组）：精简展示也不得隐藏 */
    @Column(columnDefinition = "text")
    public String counterevidence;

    @Column(nullable = false)
    public Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = Instant.now();
    }
}
