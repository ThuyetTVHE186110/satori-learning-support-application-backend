package com.fu.user.entity;

/**
 * User roles in the system.
 * Maps to PostgreSQL ENUM: user_role ('LEARNER', 'TEACHER', 'MANAGER', 'ADMIN')
 * 
 * Note: ADMIN is mapped to MANAGER at application layer (only 1 Manager manages the app)
 */
public enum UserRole {
    LEARNER,    // Student/Learner - can access learning features
    TEACHER,    // Teacher - can manage classes, assignments
    MANAGER,    // Manager - full system administration
    ADMIN       // Legacy - treated as MANAGER
}
