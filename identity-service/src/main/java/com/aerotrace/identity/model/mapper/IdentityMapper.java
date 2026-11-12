package com.aerotrace.identity.model.mapper;

import com.aerotrace.identity.model.domain.AppUser;
import com.aerotrace.identity.model.entity.AppUserEntity;
import org.mapstruct.Mapper;

/** MapStruct mapper from persistence entities to the models the rest of the service uses. */
@Mapper(componentModel = "spring")
public interface IdentityMapper {

  /**
   * Converts a stored user to its domain model.
   *
   * @param entity the stored user, with its roles loaded
   * @return the user as a detached, immutable value
   */
  AppUser toDomain(AppUserEntity entity);
}
