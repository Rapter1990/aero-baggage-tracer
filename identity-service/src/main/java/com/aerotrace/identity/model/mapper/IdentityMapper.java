package com.aerotrace.identity.model.mapper;

import com.aerotrace.common.security.Role;
import com.aerotrace.identity.constant.AuthConstants;
import com.aerotrace.identity.model.domain.AppUser;
import com.aerotrace.identity.model.dto.response.RegisteredUserResponse;
import com.aerotrace.identity.model.entity.AppUserEntity;
import java.util.List;
import java.util.Set;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

/** MapStruct mapper between persistence entities, the domain model and the API responses. */
@Mapper(componentModel = "spring")
public interface IdentityMapper {

  /** Converts a stored user (roles loaded) to its immutable domain model. */
  AppUser toDomain(AppUserEntity entity);

  /** The registration response. The passenger reference and password hash are not part of it. */
  @Mapping(target = "roles", source = "roles", qualifiedByName = "roleNames")
  @Mapping(target = "status", source = "enabled", qualifiedByName = "statusOf")
  RegisteredUserResponse toRegisteredUserResponse(AppUser user);

  /** Role names, sorted so responses and tokens are stable. */
  @Named("roleNames")
  default List<String> roleNames(Set<Role> roles) {
    return roles.stream().map(Role::name).sorted().toList();
  }

  @Named("statusOf")
  default String statusOf(boolean enabled) {
    return enabled ? AuthConstants.STATUS_ACTIVE : AuthConstants.STATUS_DISABLED;
  }
}
