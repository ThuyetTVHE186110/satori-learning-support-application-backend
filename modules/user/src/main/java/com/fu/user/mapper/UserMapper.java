package com.fu.user.mapper;

import com.fu.user.dto.CreateUserRequest;
import com.fu.user.dto.UserResponse;
import com.fu.user.entity.User;
import org.mapstruct.*;

import java.util.List;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface UserMapper {

    /**
     * Map CreateUserRequest to User entity.
     * Note: passwordHash, role, and status are set by service layer.
     * Role mapping is explicitly ignored - service layer validates permissions and sets role.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "deletedBy", ignore = true)
    @Mapping(target = "passwordHash", ignore = true)
    @Mapping(target = "role", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "avatarUrl", ignore = true)
    @Mapping(target = "authProvider", ignore = true)
    @Mapping(target = "googleId", ignore = true)
    @Mapping(target = "activationToken", ignore = true)
    @Mapping(target = "activationTokenExpiresAt", ignore = true)
    @Mapping(target = "passwordResetToken", ignore = true)
    @Mapping(target = "passwordResetTokenExpiresAt", ignore = true)
    @Mapping(target = "lastPasswordChangeAt", ignore = true)
    @Mapping(target = "failedLoginAttempts", ignore = true)
    @Mapping(target = "lockedUntil", ignore = true)
    @Mapping(target = "lastLoginAt", ignore = true)
    @Mapping(target = "emailVerifiedAt", ignore = true)
    @Mapping(target = "gender", ignore = true)
    @Mapping(target = "displayName", ignore = true)
    User toEntity(CreateUserRequest request);

    /**
     * Map User entity to UserResponse.
     */
    @Mapping(target = "fullName", expression = "java(buildFullName(user))")
    @Mapping(target = "emailVerified", expression = "java(user.getEmailVerifiedAt() != null)")
    UserResponse toResponse(User user);

    List<UserResponse> toResponseList(List<User> users);
    
    /**
     * Build fullName from firstName and lastName.
     */
    default String buildFullName(User user) {
        if (user.getFirstName() == null && user.getLastName() == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (user.getFirstName() != null) sb.append(user.getFirstName());
        if (user.getLastName() != null) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(user.getLastName());
        }
        return sb.length() > 0 ? sb.toString() : null;
    }
}
