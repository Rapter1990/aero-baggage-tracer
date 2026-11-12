package com.aerotrace.identity.repository;

import com.aerotrace.identity.model.entity.AppUserEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Access to users. Lookups are by the lower-cased username or email. */
public interface AppUserRepository extends JpaRepository<AppUserEntity, UUID> {

  /** The user with its roles, in one query. */
  @Query("select u from AppUserEntity u left join fetch u.roles where u.username = :username")
  Optional<AppUserEntity> findByUsername(@Param("username") String username);

  @Query("select u from AppUserEntity u left join fetch u.roles where u.email = :email")
  Optional<AppUserEntity> findByEmail(@Param("email") String email);
}
