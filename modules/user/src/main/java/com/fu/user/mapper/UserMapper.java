package com.fu.user.mapper;

import com.fu.user.dto.CreateUserRequest;
import com.fu.user.dto.UserResponse;
import com.fu.user.entity.User;
import org.mapstruct.*;

import java.util.List;

@Mapper(componentModel = "spring")
public interface UserMapper {

    /**
     * Map CreateUserRequest to User entity.
     * Note: passwordHash, role, status are set in service layer.
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
    @Mapping(target = "fullName", expression = "java(user.getFullName())")
    UserResponse toResponse(User user);

    List<UserResponse> toResponseList(List<User> users);
}
