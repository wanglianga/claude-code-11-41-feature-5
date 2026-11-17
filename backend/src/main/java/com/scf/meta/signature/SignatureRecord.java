package com.scf.meta.signature;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** 签名记录：来自签名索引来源；撤销会影响以其为唯一依据的身份。 */
@Entity
@Table(name = "signature_record")
public class SignatureRecord {

    public enum Status { VALID, REVOKED }

    @Id
    public String id;

    @Column(nullable = false)
    public String sourceArtifactId;

    @Column(nullable = false, length = 128)
    public String subjectHash;

    @Column(length = 255)
    public String signer;

    @Column(nullable = false, length = 16)
    public String status = "VALID";

    public Instant revokedAt;

    /** 签名的业务有效起点（空表示自接收起有效） */
    public Instant validFrom;

    @Column(nullable = false)
    public Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = Instant.now();
    }
}
