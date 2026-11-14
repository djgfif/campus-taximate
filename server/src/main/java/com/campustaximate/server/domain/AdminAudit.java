package com.campustaximate.server.domain;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;
@Entity @Getter @Setter @NoArgsConstructor
@Table(uniqueConstraints=@UniqueConstraint(name="uk_admin_audit_key",columnNames={"operation_key"}))
public class AdminAudit {
@Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
private Long actorId; private Instant createdAt; private String action; private String targetType; private Long targetId;
private String decision; @Column(length=2000) private String evidence;
@Column(length=255) private String operationKey; @Column(length=2000) private String fingerprint;
}
