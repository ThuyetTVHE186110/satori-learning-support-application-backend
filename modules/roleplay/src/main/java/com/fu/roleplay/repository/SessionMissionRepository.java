package com.fu.roleplay.repository;

import com.fu.roleplay.entity.SessionMission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SessionMissionRepository extends JpaRepository<SessionMission, UUID> {

    List<SessionMission> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);
}
