package com.fu.infrastructure.learner;

import java.util.Optional;
import java.util.UUID;

/**
 * Cross-module contract for retrieving a learner's profile (skill scores + insights).
 * Implemented by the analytics module, consumed by conversation/roleplay prompt builders.
 */
public interface LearnerProfileProvider {

    Optional<LearnerProfile> getLearnerProfile(UUID userId);
}
