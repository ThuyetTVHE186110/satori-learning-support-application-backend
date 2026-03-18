package com.fu.user.repository;

import com.fu.user.entity.UserLearningPreferences;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserLearningPreferencesRepository extends JpaRepository<UserLearningPreferences, UUID> {

    Optional<UserLearningPreferences> findByUserId(UUID userId);
}
