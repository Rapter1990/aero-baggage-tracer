package com.aerotrace.identity.repository;

import com.aerotrace.identity.model.entity.AuthAuditEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** Append-only access to the authentication audit trail. */
public interface AuthAuditRepository extends JpaRepository<AuthAuditEntity, Long> {}
