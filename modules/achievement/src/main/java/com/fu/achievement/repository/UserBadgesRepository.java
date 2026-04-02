package com.fu.achievement.repository;

import com.fu.achievement.entity.UserBadges;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface UserBadgesRepository extends JpaRepository<UserBadges, UUID> {
    
    long countByBadgeId(UUID badgeId);
}
