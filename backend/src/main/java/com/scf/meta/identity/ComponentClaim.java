package com.scf.meta.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 组件声明：从某一来源载荷中解析出的单条组件断言。
 * RESOLVED 表示该来源是构建实际解析证据（SBOM/锁文件/构建日志/签名），
 * INFERRED 表示仅由包仓元数据或源码扫描推断。
 */
@Entity
@Table(name = "component_claim")
public class ComponentClaim {

    public enum Kind { RESOLVED, INFERRED, SIGNATURE }

    @Id
    public String id;

    @Column(nullable = false)
    public String sourceArtifactId;

    @Column(length = 32)
    public String ecosystem;

    @Column(length = 255)
    public String namespace;

    @Column(nullable = false, length = 255)
    public String name;

    @Column(length = 255)
    public String version;

    @Column(length = 255)
    public String publisher;

    @Column(length = 16)
    public String hashAlg;

    @Column(length = 128)
    public String hashValue;

    /** 构建上下文（构建标识），用于按构建确认身份 */
    @Column(length = 128)
    public String buildContext;

    @Column(nullable = false, length = 16)
    public String kind;

    /** 归并到的身份 */
    public String identityId;

    /** 依赖范围（runtime/test 等，缺省按 runtime 处理） */
    @Column(length = 32)
    public String scope;

    /** 关系的业务有效起点（缺省继承来源的有效区间） */
    public Instant validFrom;

    /** 关系的业务有效终点（缺省继承来源的有效区间） */
    public Instant validTo;

    @Column(nullable = false)
    public Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = Instant.now();
    }

    /** 声明分组键：生态缺省时按 unknown 处理，不跨生态合并 */
    public String groupKey() {
        return String.join("|",
                ecosystem == null || ecosystem.isBlank() ? "unknown" : ecosystem,
                namespace == null ? "" : namespace,
                name == null ? "" : name,
                version == null ? "" : version);
    }
}
