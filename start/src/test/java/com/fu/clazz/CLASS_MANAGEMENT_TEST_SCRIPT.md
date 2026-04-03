# Class Management Integration Test Script

## Overview
This document contains detailed test scripts for all class management API endpoints in Satori Learning Support Application backend.

## Test Environment Setup

### Pre-conditions
- Test DB running via `application-test.properties`
- PostgreSQL `satori_test` database created and initialized
- Docker environment up and running
- EmailService mocked to prevent actual email sending
- CloudinaryService mocked to prevent actual file uploads
- SQL cleanup handled by @Sql annotations for test isolation

---

## Section 1 — View Class List Tests (GET /api/v1/classes)

### Test Case ID: TC_CLM_VL_001
**Test Case Description:** Manager views all non-deleted classes with pagination

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher user exists in database
- Course exists with ACTIVE status
- Classes inserted via classRepository.save():
- "Class A" (teacherId=teacher1, courseId=course, isActive=true, maxStudents=8)
- "Class B" (teacherId=teacher1, courseId=course, isActive=true, maxStudents=8)
- "Class C" (teacherId=teacher1, courseId=course, isActive=false, maxStudents=8)

**Test Case Procedure:**
1. Client sends GET /api/v1/classes?page=0&size=10 with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.getClasses() with pagination params.
4. ClassService retrieves class list via ClassRepository.findAllWithFilters().
5. ClassRepository executes database query and returns paginated class list.
6. System returns the paginated class list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.totalElements = 3
- data.content contains classes with names: "Class C", "Class B", "Class A" (sorted by createdAt DESC)
- data.pageNumber = 0
- data.pageSize = 10
- data.first = true
- data.last = true (only 3 items)
All items contain required fields: id, name, teacherId, courseId, isActive, maxStudents, startDate, endDate

---

### Test Case ID: TC_CLM_VL_002
**Test Case Description:** Teacher sees only assigned active classes (auto-filtered)

**Pre-conditions:**
- Teacher1 accessToken is available (from prior login)
- Teacher1 and Teacher2 users exist in database
- Course exists with ACTIVE status
- Classes inserted via classRepository.save():
- "T1 Active 1" (teacherId=teacher1, courseId=course, isActive=true)
- "T1 Active 2" (teacherId=teacher1, courseId=course, isActive=true)
- "T1 Inactive" (teacherId=teacher1, courseId=course, isActive=false)
- "T2 Active" (teacherId=teacher2, courseId=course, isActive=true)

**Test Case Procedure:**
1. Client sends GET /api/v1/classes?page=0&size=10 with Authorization: Bearer {teacher1Token}.
2. AuthFilter validates token and resolves TEACHER user identity.
3. ClassController calls ClassService.getClasses() with teacher auto-filter.
4. ClassService retrieves filtered class list via ClassRepository.findAllWithFilters().
5. ClassRepository executes database query with teacherId and isActive filters.
6. System returns the filtered paginated class list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.totalElements = 2
- data.content contains only "T1 Active 1" and "T1 Active 2"
- data.pageNumber = 0
- data.pageSize = 10

---

### Test Case ID: TC_CLM_VL_003
**Test Case Description:** Manager filters by keyword, teacher, active status, and course

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher1 and Teacher2 users exist in database
- Course1 (Japanese N5) and Course2 (Japanese N4) exist with ACTIVE status
- Classes inserted via classRepository.save():
- "N5 A" (teacherId=teacher1, courseId=course1, isActive=true) - MATCH
- "N5 B" (teacherId=teacher1, courseId=course1, isActive=false) - status mismatch
- "N5 C" (teacherId=teacher2, courseId=course1, isActive=true) - teacher mismatch
- "Other" (teacherId=teacher1, courseId=course1, isActive=true) - keyword mismatch
- "N5 D" (teacherId=teacher1, courseId=course2, isActive=true) - course mismatch
- "N5 Deleted" (teacherId=teacher1, courseId=course1, isActive=true) - soft-deleted

**Test Case Procedure:**
1. Client sends GET /api/v1/classes?keyword=N5&teacherId={teacher1}&courseId={course1}&isActive=true with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.getClasses() with combined filters.
4. ClassService retrieves filtered class list via ClassRepository.findWithFilters().
5. ClassRepository executes database query with all filters and excludes soft-deleted.
6. System returns the filtered paginated class list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.totalElements = 1
- data.content contains class with name = "N5 A"

---

### Test Case ID: TC_CLM_VL_004
**Test Case Description:** Teacher filters by keyword and course only

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course1 (Japanese N5) and Course2 (Japanese N4) exist with ACTIVE status
- Classes inserted via classRepository.save():
- "Kana Basics" (teacherId=teacher, courseId=course1, isActive=true)
- "Kana Review" (teacherId=teacher, courseId=course2, isActive=true)
- "Grammar" (teacherId=teacher, courseId=course1, isActive=true)
- "Kana Inactive" (teacherId=teacher, courseId=course1, isActive=false)

**Test Case Procedure:**
1. Client sends GET /api/v1/classes?keyword=Kana&courseId={course1}&isActive=false with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves TEACHER user identity.
3. ClassController calls ClassService.getClasses() with filters (teacher auto-applied).
4. ClassService retrieves filtered class list via ClassRepository.findWithFilters().
5. ClassRepository executes database query with keyword and course filters.
6. System returns the filtered paginated class list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.totalElements = 1
- data.content contains class with name = "Kana Basics"

---

### Test Case ID: TC_CLM_VL_005
**Test Case Description:** Empty results show no-classes message

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- No classes exist in database

**Test Case Procedure:**
1. Client sends GET /api/v1/classes?keyword=NoMatch with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.getClasses() with keyword filter.
4. ClassService retrieves empty list via ClassRepository.findWithFilters().
5. System returns empty result with message.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- message = "Không có lớp học nào."

---

## Section 2 — View Class Detail Tests (GET /api/v1/classes/{classId})

### Test Case ID: TC_CLM_VD_001
**Test Case Description:** Manager views any non-deleted class detail

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher user exists with firstName="Test", lastName="Teacher"
- Course exists with name="Japanese N5", level="N5", status="ACTIVE"
- Class exists with name="Test Class", teacherId=teacher, courseId=course

**Test Case Procedure:**
1. Client sends GET /api/v1/classes/{classId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.getClassById().
4. ClassService retrieves class by ID via ClassRepository.findByIdAndNotDeleted().
5. ClassRepository executes database query and returns the class object.
6. ClassService enriches response with course and teacher information.
7. System returns the class detail response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.id = non-empty UUID
- data.name = "Test Class"
- data.description = "Test class"
- data.teacherId = teacher UUID
- data.courseId = course UUID
- data.courseName = "Japanese N5"
- data.courseLevel = "N5"
- data.courseStatus = "ACTIVE"
- data.maxStudents = 8
- data.startDate = non-null date
- data.endDate = non-null date
- data.isActive = true
- data.memberCount = 0
- data.teacherName = "Test Teacher"
- data.teacherEmail = "teacher@test.com"
- data.createdAt = non-null timestamp
- data.updatedAt = non-null timestamp

---

### Test Case ID: TC_CLM_VD_002
**Test Case Description:** Teacher views only assigned class detail

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course exists with ACTIVE status
- Class exists with teacherId=teacher

**Test Case Procedure:**
1. Client sends GET /api/v1/classes/{classId} with Authorization: Bearer {teacherToken}.
2. AuthFilter validates token and resolves TEACHER user identity.
3. ClassController calls ClassService.getClassById().
4. ClassService retrieves class by ID via ClassRepository.findByIdAndNotDeleted().
5. ClassService validates teacher has access to this class.
6. System returns the class detail response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.id = non-empty UUID

---

### Test Case ID: TC_CLM_VD_003
**Test Case Description:** Teacher views class NOT assigned → 400 ACCESS_DENIED

**Pre-conditions:**
- Teacher1 accessToken is available (from prior login)
- Teacher2 exists in database
- Course exists with ACTIVE status
- Class exists with teacherId=teacher2

**Test Case Procedure:**
1. Client sends GET /api/v1/classes/{classId} with Authorization: Bearer {teacher1Token}.
2. AuthFilter validates token and resolves TEACHER1 user identity.
3. ClassController calls ClassService.getClassById().
4. ClassService retrieves class by ID via ClassRepository.findByIdAndNotDeleted().
5. ClassService detects teacher is not assigned to this class.
6. System throws BusinessException with ACCESS_DENIED.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "ACCESS_DENIED"

---

### Test Case ID: TC_CLM_VD_004
**Test Case Description:** Class not found → 404 NOT_FOUND

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Class ID does not exist in database

**Test Case Procedure:**
1. Client sends GET /api/v1/classes/{nonExistentId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.getClassById().
4. ClassService retrieves class by ID via ClassRepository.findByIdAndNotDeleted().
5. ClassRepository executes database query and returns empty (no class found).
6. ClassService throws BusinessException with NOT_FOUND.
7. System returns the not found error response.

**Expected Results:** HTTP 404 NOT FOUND
Response JSON:
- success = false
- code = "NOT_FOUND"
- message = "Lớp học không tồn tại."

---

## Section 3 — View Class Members Tests (GET /api/v1/classes/{classId}/members)

### Test Case ID: TC_CLM_VM_001
**Test Case Description:** Manager views active members, removed excluded, sorted by joinDate DESC

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher user exists in database
- Course exists with ACTIVE status
- Class exists with teacherId=teacher
- Learner users exist: learner1@test.com, learner2@test.com, learner3@test.com, removed@test.com
- ClassMember records inserted:
- learner1 joined 3 days ago
- learner2 joined 1 day ago
- learner3 joined 2 days ago
- removed learner has removedAt set (removed)

**Test Case Procedure:**
1. Client sends GET /api/v1/classes/{classId}/members?page=0&size=10 with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.getClassMembers().
4. ClassService retrieves active members via ClassRepository.findActiveMembersByClassId().
5. ClassRepository executes database query with removedAt IS NULL filter.
6. System returns paginated member list sorted by joinedAt DESC.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.content.length() = 3
- data.content[0].email = "learner2@test.com" (most recent)
- data.content[1].email = "learner3@test.com"
- data.content[2].email = "learner1@test.com" (oldest)
Each item contains: userId, fullName, email, role = "LEARNER"
No item with email = "removed@test.com"

---

### Test Case ID: TC_CLM_VM_002
**Test Case Description:** Teacher views members of assigned class

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course exists with ACTIVE status
- Class exists with teacherId=teacher
- Two learners added to class via classMemberRepository.save()

**Test Case Procedure:**
1. Client sends GET /api/v1/classes/{classId}/members?page=0&size=10 with Authorization: Bearer {teacherToken}.
2. AuthFilter validates token and resolves TEACHER user identity.
3. ClassController calls ClassService.getClassMembers().
4. ClassService validates teacher has access to this class.
5. ClassService retrieves active members via ClassRepository.findActiveMembersByClassId().
6. System returns the member list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.content.length() = 2

---

### Test Case ID: TC_CLM_VM_003
**Test Case Description:** Teacher not assigned → 400 ACCESS_DENIED

**Pre-conditions:**
- Teacher1 accessToken is available (from prior login)
- Teacher2 exists in database
- Course exists with ACTIVE status
- Class exists with teacherId=teacher2

**Test Case Procedure:**
1. Client sends GET /api/v1/classes/{classId}/members with Authorization: Bearer {teacher1Token}.
2. AuthFilter validates token and resolves TEACHER1 user identity.
3. ClassController calls ClassService.getClassMembers().
4. ClassService retrieves class by ID and validates teacher access.
5. ClassService detects teacher is not assigned to this class.
6. System throws BusinessException with ACCESS_DENIED.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "ACCESS_DENIED"
- message = "Bạn không có quyền xem thành viên của lớp học này."

---

### Test Case ID: TC_CLM_VM_004
**Test Case Description:** Class not found → 404 NOT_FOUND

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Class ID does not exist in database

**Test Case Procedure:**
1. Client sends GET /api/v1/classes/{classId}/members with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.getClassMembers().
4. ClassService retrieves class by ID via ClassRepository.findByIdAndNotDeleted().
5. ClassRepository returns empty (no class found).
6. ClassService throws BusinessException with NOT_FOUND.
7. System returns the not found error response.

**Expected Results:** HTTP 404 NOT FOUND
Response JSON:
- success = false
- code = "NOT_FOUND"
- message = "Lớp học không tồn tại."

---

### Test Case ID: TC_CLM_VM_005
**Test Case Description:** Empty member list shows no-learners message

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher user exists in database
- Course exists with ACTIVE status
- Class exists with teacherId=teacher
- No members added to class

**Test Case Procedure:**
1. Client sends GET /api/v1/classes/{classId}/members?page=0&size=10 with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.getClassMembers().
4. ClassService retrieves empty member list via ClassRepository.findActiveMembersByClassId().
5. System returns empty result with message.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- message = "Không có học viên nào trong lớp này."

---

### Test Case ID: TC_CLM_VM_006
**Test Case Description:** Search members by keyword

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher user exists in database
- Course exists with ACTIVE status
- Class exists with teacherId=teacher
- Learners exist: alice@test.com, bob@test.com
- Both learners added to class

**Test Case Procedure:**
1. Client sends GET /api/v1/classes/{classId}/members?keyword=alice with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.getClassMembers() with keyword filter.
4. ClassService searches members by keyword via ClassRepository.searchActiveMembersByClassId().
5. ClassRepository executes database query with keyword search.
6. System returns filtered member list.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.content.length() = 1
- data.content[0].email = "alice@test.com"

---

### Test Case ID: TC_CLM_VM_008
**Test Case Description:** No learner found after keyword search → empty message

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher user exists in database
- Course exists with ACTIVE status
- Class exists with teacherId=teacher
- Learner existing@test.com added to class

**Test Case Procedure:**
1. Client sends GET /api/v1/classes/{classId}/members?keyword=zzz_no_match_zzz with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.getClassMembers() with keyword filter.
4. ClassService searches members by keyword via ClassRepository.searchActiveMembersByClassId().
5. ClassRepository returns empty results.
6. System returns empty result with message.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- message = "Không có học viên nào trong lớp này."

---

### Test Case ID: TC_CLM_VM_009
**Test Case Description:** Unauthorized access: LEARNER token → 403 Forbidden

**Pre-conditions:**
- Learner accessToken is available (from prior login)

**Test Case Procedure:**
1. Client sends GET /api/v1/classes/{classId}/members with Authorization: Bearer {learnerToken}.
2. AuthFilter validates token and resolves LEARNER user identity.
3. SecurityConfig denies access due to insufficient permissions (RBAC).
4. System returns the access denied error response.

**Expected Results:** HTTP 403 FORBIDDEN

---

### Test Case ID: TC_CLM_VM_010
**Test Case Description:** Cannot view members of a deleted class → 404 NOT_FOUND

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher user exists in database
- Course exists with ACTIVE status
- Class exists but has been soft-deleted (deletedAt and deletedBy set)

**Test Case Procedure:**
1. Client sends GET /api/v1/classes/{classId}/members with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.getClassMembers().
4. ClassService retrieves class by ID via ClassRepository.findByIdAndNotDeleted().
5. ClassRepository returns empty (soft-deleted class filtered out).
6. ClassService throws BusinessException with NOT_FOUND.
7. System returns the not found error response.

**Expected Results:** HTTP 404 NOT FOUND
Response JSON:
- success = false
- code = "NOT_FOUND"
- message = "Lớp học không tồn tại."

---

## Section 4 — Create Class Tests (POST /api/v1/classes)

### Test Case ID: TC_CLM_CC_001
**Test Case Description:** Create class successfully, auto-activated if startDate ≤ current date

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher user exists in database
- Course exists with ACTIVE status

**Test Case Procedure:**
1. Client sends POST /api/v1/classes with Authorization: Bearer {token} and JSON body:
   {
     "name": "Japanese N5 Class",
     "description": "Comprehensive Japanese N5 course for beginners",
     "teacherId": "{teacherId}",
     "courseId": "{courseId}",
     "maxStudents": 8,
     "startDate": "{today}",
     "endDate": "{3MonthsFromNow}"
   }
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.createClass().
4. ClassService validates class name uniqueness via ClassRepository.findByName().
5. ClassRepository executes database query and returns empty (no existing class).
6. ClassService validates teacher exists and has TEACHER role via UserRepository.
7. ClassService validates course exists and is ACTIVE via CourseRepository.
8. ClassService validates date range (endDate > startDate).
9. ClassService creates new Class entity and determines isActive based on startDate comparison:
   - If startDate ≤ current date: isActive = true (auto-activated)
   - If startDate > current date: isActive = false (auto-inactive)
10. ClassRepository saves the new class.
11. System returns the created class response.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- data.id = non-empty UUID
- data.name = "Japanese N5 Class"
- data.isActive = true (auto-activated since startDate ≤ current date)
- data.teacherId = teacher UUID
- data.courseId = course UUID
Database state: class.isActive = true, class.deletedAt = null

---

### Test Case ID: TC_CLM_CC_001b
**Test Case Description:** Create class with future startDate → auto-inactive

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher user exists in database
- Course exists with ACTIVE status

**Test Case Procedure:**
1. Client sends POST /api/v1/classes with Authorization: Bearer {token} and JSON body:
   {
     "name": "Future Japanese N5 Class",
     "description": "Japanese N5 course starting next month",
     "teacherId": "{teacherId}",
     "courseId": "{courseId}",
     "maxStudents": 8,
     "startDate": "{30DaysFromNow}",
     "endDate": "{3MonthsFromNow}"
   }
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.createClass().
4. ClassService validates class name uniqueness via ClassRepository.findByName().
5. ClassRepository executes database query and returns empty (no existing class).
6. ClassService validates teacher exists and has TEACHER role via UserRepository.
7. ClassService validates course exists and is ACTIVE via CourseRepository.
8. ClassService validates date range (endDate > startDate).
9. ClassService creates new Class entity and determines isActive based on startDate comparison:
   - startDate > current date, so isActive = false (auto-inactive)
10. ClassRepository saves the new class.
11. System returns the created class response.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- data.id = non-empty UUID
- data.name = "Future Japanese N5 Class"
- data.isActive = false (auto-inactive since startDate > current date)
- data.teacherId = teacher UUID
- data.courseId = course UUID
Database state: class.isActive = false, class.deletedAt = null

---

### Test Case ID: TC_CLM_CC_002
**Test Case Description:** Duplicate class name → CLASS_NAME_EXISTS

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher user exists in database
- Course exists with ACTIVE status
- Class "Japanese N5 Class" already exists in database

**Test Case Procedure:**
1. Client sends POST /api/v1/classes with Authorization: Bearer {token} and JSON body with duplicate name.
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.createClass().
4. ClassService validates class name uniqueness via ClassRepository.findByName().
5. ClassRepository executes database query and returns existing class.
6. ClassService detects name conflict and throws BusinessException with CLASS_NAME_EXISTS.
7. System returns the duplicate name error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "CLASS_NAME_EXISTS"

---

### Test Case ID: TC_CLM_CC_004
**Test Case Description:** startDate in the future → class created as inactive

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher user exists in database
- Course exists with ACTIVE status

**Test Case Procedure:**
1. Client sends POST /api/v1/classes with Authorization: Bearer {token} and JSON body:
   {
     "name": "Future Class",
     "teacherId": "{teacherId}",
     "courseId": "{courseId}",
     "maxStudents": 8,
     "startDate": "{10DaysFromNow}"
   }
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.createClass().
4. ClassService validates class name uniqueness via ClassRepository.findByName().
5. ClassRepository executes database query and returns empty (no existing class).
6. ClassService validates teacher exists and has TEACHER role via UserRepository.
7. ClassService validates course exists and is ACTIVE via CourseRepository.
8. ClassService creates new Class entity and determines isActive based on startDate comparison:
   - startDate > current date, so isActive = false (auto-inactive)
9. ClassRepository saves the new class.
10. System returns the created class response.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- data.id = non-empty UUID
- data.name = "Future Class"
- data.isActive = false (auto-inactive since startDate > current date)
- data.teacherId = teacher UUID
- data.courseId = course UUID
Database state: class.isActive = false, class.deletedAt = null

---

### Test Case ID: TC_CLM_CC_005
**Test Case Description:** teacherId is a LEARNER → INVALID_TEACHER

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Learner user exists in database
- Course exists with ACTIVE status

**Test Case Procedure:**
1. Client sends POST /api/v1/classes with Authorization: Bearer {token} and JSON body with teacherId pointing to LEARNER.
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.createClass().
4. ClassService validates teacher role via UserRepository.
5. ClassService detects user is not a TEACHER and throws BusinessException with INVALID_TEACHER.
6. System returns the validation error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "INVALID_TEACHER"

---

### Test Case ID: TC_CLM_CC_006
**Test Case Description:** INACTIVE course → INVALID_COURSE_STATUS

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher user exists in database
- Course exists with INACTIVE status

**Test Case Procedure:**
1. Client sends POST /api/v1/classes with Authorization: Bearer {token} and JSON body with courseId pointing to INACTIVE course.
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.createClass().
4. ClassService validates course status via CourseRepository.
5. ClassService detects course is not ACTIVE and throws BusinessException with INVALID_COURSE_STATUS.
6. System returns the validation error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "INVALID_COURSE_STATUS"

---

### Test Case ID: TC_CLM_CC_007
**Test Case Description:** TEACHER token → 403 Forbidden

**Pre-conditions:**
- Teacher accessToken is available (from prior login)

**Test Case Procedure:**
1. Client sends POST /api/v1/classes with Authorization: Bearer {teacherToken} and JSON body.
2. AuthFilter validates token and resolves TEACHER user identity.
3. SecurityConfig denies access due to insufficient permissions (RBAC).
4. System returns the access denied error response.

**Expected Results:** HTTP 403 FORBIDDEN
Response JSON:
- success = false
- code = "FORBIDDEN"

---

### Test Case ID: TC_CLM_CC_008
**Test Case Description:** Create class without description: succeeds with null description

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher user exists in database
- Course exists with ACTIVE status

**Test Case Procedure:**
1. Client sends POST /api/v1/classes with Authorization: Bearer {token} and JSON body without description field.
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.createClass().
4. ClassService validates all required fields (description is optional).
5. ClassService creates new Class entity with description=null and saves via ClassRepository.save().
6. ClassRepository saves the new class.
7. System returns the created class response.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- data.isActive = true
- data.name = "No Desc Class"
Database state: class.description = null, class.isActive = true

---

### Test Case ID: TC_CLM_CC_009
**Test Case Description:** Missing required fields (name, teacherId, courseId) → 400 VALIDATION_ERROR

**Pre-conditions:**
- Manager accessToken is available (from prior login)

**Test Case Procedure:**
1. Client sends POST /api/v1/classes with Authorization: Bearer {token} and empty JSON body {}.
2. Request validation fails at controller level due to missing required fields.
3. System returns the validation error response before reaching ClassService.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "VALIDATION_ERROR"
Database state: No class created

---

### Test Case ID: TC_CLM_CC_010
**Test Case Description:** Teacher ID does not exist in database → INVALID_TEACHER

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Course exists with ACTIVE status

**Test Case Procedure:**
1. Client sends POST /api/v1/classes with Authorization: Bearer {token} and JSON body with non-existent teacherId.
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.createClass().
4. ClassService validates teacher existence via UserRepository.
5. ClassRepository returns empty (no user found).
6. ClassService throws BusinessException with INVALID_TEACHER.
7. System returns the validation error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "INVALID_TEACHER"

---

### Test Case ID: TC_CLM_CC_011
**Test Case Description:** Course ID does not exist or has been deleted → INVALID_COURSE

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher user exists in database

**Test Case Procedure:**
1. Client sends POST /api/v1/classes with Authorization: Bearer {token} and JSON body with non-existent courseId.
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.createClass().
4. ClassService validates course existence via CourseRepository.
5. CourseRepository returns empty (no course found).
6. ClassService throws BusinessException with INVALID_COURSE.
7. System returns the validation error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "INVALID_COURSE"

---

### Test Case ID: TC_CLM_CC_012
**Test Case Description:** maxStudents defaults to 8 when not provided

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher user exists in database
- Course exists with ACTIVE status

**Test Case Procedure:**
1. Client sends POST /api/v1/classes with Authorization: Bearer {token} and JSON body without maxStudents field.
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.createClass().
4. ClassService creates new Class entity without setting maxStudents.
5. ClassRepository.save() applies database default value of 8.
6. System returns the created class response.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- data.maxStudents = 8
Database state: class.maxStudents = 8

---

## Section 5 — Update Class Tests (PUT /api/v1/classes/{classId})

### Test Case ID: TC_CLM_UC_001
**Test Case Description:** Update success: returns updated details with memberCount, teacher info, updatedBy and updatedAt recorded

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher user exists in database
- Course exists with ACTIVE status
- Class exists with name="Old Name"
- One learner added to class

**Test Case Procedure:**
1. Client sends PUT /api/v1/classes/{classId} with Authorization: Bearer {token} and JSON body:
   {
     "name": "New Name",
     "description": "Updated desc",
     "startDate": "{2DaysFromNow}",
     "endDate": "{6MonthsFromNow}"
   }
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.updateClass().
4. ClassService retrieves class by ID via ClassRepository.findByIdAndNotDeleted().
5. ClassRepository executes database query and returns the class object.
6. ClassService updates class fields and sets updatedBy=managerId, updatedAt=now.
7. ClassRepository saves the updated class.
8. System returns the updated class response with enriched data.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- message = "Cập nhật thông tin lớp học thành công."
- data.name = "New Name"
- data.description = "Updated desc"
- data.memberCount = 1
- data.teacherId = teacher UUID
- data.teacherName = non-empty string
- data.updatedBy = manager UUID
- data.updatedAt = non-null timestamp
Database state: class.name = "New Name", class.updatedBy = managerId, class.updatedAt > beforeUpdate

---

### Test Case ID: TC_CLM_UC_002
**Test Case Description:** Change teacher: old teacher loses access, new teacher gains access

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher1 and Teacher2 users exist in database
- Course exists with ACTIVE status
- Class exists with teacherId=teacher1

**Test Case Procedure:**
1. Client sends PUT /api/v1/classes/{classId} with Authorization: Bearer {managerToken} and JSON body:
   { "teacherId": "{teacher2Id}" }
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.updateClass().
4. ClassService retrieves class and updates teacherId to teacher2.
5. ClassRepository saves the updated class.
6. System returns the updated class response.
7. Teacher1 lists classes → class not visible
8. Teacher2 lists classes → class visible

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.teacherId = teacher2 UUID
Teacher1 class list: 0 classes
Teacher2 class list: 1 class

---

### Test Case ID: TC_CLM_UC_003
**Test Case Description:** Close class via /close endpoint: disappears from teacher list, assign learner blocked, learners become inactive

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher user exists in database
- Course exists with ACTIVE status
- Class exists with isActive=true
- Learner added to class

**Test Case Procedure:**
1. Client sends PUT /api/v1/classes/{classId}/close with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.closeClass().
4. ClassService updates class to isActive=false.
5. ClassRepository saves the updated class.
6. ClassService updates all active members to INACTIVE status.
7. System returns the updated class response.
8. Teacher lists classes → class not visible (teacher sees only active classes)
9. Attempt to assign new learner → blocked with CLASS_INACTIVE
10. Check existing learner status → INACTIVE

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.isActive = false
Teacher class list: 0 classes
New learner assignment: HTTP 400 with CLASS_INACTIVE
Existing learner status: INACTIVE
Database state: class.isActive = false, learner.status = INACTIVE

---

### Test Case ID: TC_CLM_UC_004
**Test Case Description:** Reactivate class: update startDate to today when no members → auto-activated, reappears in teacher list

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher user exists in database
- Course exists with ACTIVE status
- Class exists with isActive=false (future startDate)
- No members in class

**Test Case Procedure:**
1. Client sends PUT /api/v1/classes/{classId} with Authorization: Bearer {token} and JSON body:
   { "startDate": "{today}" }
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.updateClass().
4. ClassService updates startDate to today and detects no members present.
5. ClassService auto-activates class (isActive=true) when startDate ≤ today and no members.
6. ClassRepository saves the updated class.
7. System returns the updated class response.
8. Teacher lists classes → class visible
9. Assign new learner → succeeds

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.isActive = true
Teacher class list: 1 class
New learner assignment: HTTP 201 Created
Database state: class.isActive = true, class.startDate = today

---

### Test Case ID: TC_CLM_UC_005
**Test Case Description:** maxStudents below current enrollment → CAPACITY_VIOLATION

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher user exists in database
- Course exists with ACTIVE status
- Class exists with maxStudents=8
- Three learners added to class

**Test Case Procedure:**
1. Client sends PUT /api/v1/classes/{classId} with Authorization: Bearer {token} and JSON body:
   { "maxStudents": 1 }
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.updateClass().
4. ClassService retrieves class and counts current active members (3).
5. ClassService detects maxStudents (1) < current enrollment (3).
6. ClassService throws BusinessException with CAPACITY_VIOLATION.
7. System returns the validation error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "CAPACITY_VIOLATION"

---

### Test Case ID: TC_CLM_UC_007
**Test Case Description:** Update non-existing class → 404 NOT_FOUND

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Class ID does not exist in database

**Test Case Procedure:**
1. Client sends PUT /api/v1/classes/{classId} with Authorization: Bearer {token} and JSON body.
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.updateClass().
4. ClassService retrieves class by ID via ClassRepository.findByIdAndNotDeleted().
5. ClassRepository returns empty (no class found).
6. ClassService throws BusinessException with NOT_FOUND.
7. System returns the not found error response.

**Expected Results:** HTTP 404 NOT FOUND
Response JSON:
- success = false
- code = "NOT_FOUND"
- message = "Lớp học không tồn tại."

---

### Test Case ID: TC_CLM_UC_008
**Test Case Description:** Assign non-TEACHER user as teacher → INVALID_TEACHER

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher and Learner users exist in database
- Course exists with ACTIVE status
- Class exists with teacherId=teacher

**Test Case Procedure:**
1. Client sends PUT /api/v1/classes/{classId} with Authorization: Bearer {token} and JSON body:
   { "teacherId": "{learnerId}" }
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.updateClass().
4. ClassService validates teacher role via UserRepository.
5. ClassService detects user is not a TEACHER and throws BusinessException with INVALID_TEACHER.
6. System returns the validation error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "INVALID_TEACHER"
- message = "Giáo viên không tồn tại hoặc không hợp lệ."

---

### Test Case ID: TC_CLM_UC_009
**Test Case Description:** Update startDate to after current startDate when members exist → INVALID_START_DATE

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher user exists in database
- Course exists with ACTIVE status
- Class exists with startDate = today, isActive=true
- At least one member exists in class

**Test Case Procedure:**
1. Client sends PUT /api/v1/classes/{classId} with Authorization: Bearer {token} and JSON body:
   { "startDate": "{5DaysFromNow}" }
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.updateClass().
4. ClassService detects class has existing members.
5. ClassService validates startDate change and detects new startDate > old startDate.
6. ClassService throws BusinessException with INVALID_START_DATE.
7. System returns the validation error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "INVALID_START_DATE"
- message = "Không thể thay đổi startDate sang ngày muộn hơn khi lớp đã có học viên."
Database state: class.startDate unchanged

---

### Test Case ID: TC_CLM_UC_010
**Test Case Description:** Update with required name set to blank → VALIDATION_ERROR

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher user exists in database
- Course exists with ACTIVE status
- Class exists with name="Valid Class"

**Test Case Procedure:**
1. Client sends PUT /api/v1/classes/{classId} with Authorization: Bearer {token} and JSON body:
   { "name": "  " } (blank/whitespace)
2. Request validation fails at controller level due to @NotBlank violation.
3. System returns the validation error response before reaching ClassService.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "VALIDATION_ERROR"
Database state: class.name unchanged ("Valid Class")

---

### Test Case ID: TC_CLM_UC_011
**Test Case Description:** Duplicate class name on update → CLASS_NAME_EXISTS

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher user exists in database
- Course exists with ACTIVE status
- Class "Existing Name" exists in database
- Class "Another Name" exists in database

**Test Case Procedure:**
1. Client sends PUT /api/v1/classes/{classId} (for "Another Name") with Authorization: Bearer {token} and JSON body:
   { "name": "Existing Name" }
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.updateClass().
4. ClassService validates class name uniqueness via ClassRepository.findByName().
5. ClassRepository returns existing class with "Existing Name".
6. ClassService detects name conflict and throws BusinessException with CLASS_NAME_EXISTS.
7. System returns the duplicate name error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "CLASS_NAME_EXISTS"

---

### Test Case ID: TC_CLM_UC_012
**Test Case Description:** TEACHER token cannot update class → 403 Forbidden

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course exists with ACTIVE status
- Class exists with teacherId=teacher

**Test Case Procedure:**
1. Client sends PUT /api/v1/classes/{classId} with Authorization: Bearer {teacherToken} and JSON body.
2. AuthFilter validates token and resolves TEACHER user identity.
3. SecurityConfig denies access due to insufficient permissions (RBAC).
4. System returns the access denied error response.

**Expected Results:** HTTP 403 FORBIDDEN
Response JSON:
- success = false
- code = "FORBIDDEN"

---

### Test Case ID: TC_CLM_UC_013
**Test Case Description:** Cannot update a soft-deleted class → 404 NOT_FOUND

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher user exists in database
- Course exists with ACTIVE status
- Class exists but has been soft-deleted (deletedAt and deletedBy set)

**Test Case Procedure:**
1. Client sends PUT /api/v1/classes/{classId} with Authorization: Bearer {token} and JSON body.
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.updateClass().
4. ClassService retrieves class by ID via ClassRepository.findByIdAndNotDeleted().
5. ClassRepository returns empty (soft-deleted class filtered out).
6. ClassService throws BusinessException with NOT_FOUND.
7. System returns the not found error response.

**Expected Results:** HTTP 404 NOT FOUND
Response JSON:
- success = false
- code = "NOT_FOUND"
- message = "Lớp học không tồn tại."

---

## Section 6 — Assign Learner to Class Tests (POST /api/v1/classes/{classId}/members)

### Test Case ID: TC_CLM_AL_001
**Test Case Description:** Assign learner successfully → 201 Created

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher user exists in database
- Course exists with ACTIVE status
- Class exists with isActive=true
- Learner user exists with ACTIVE status

**Test Case Procedure:**
1. Client sends POST /api/v1/classes/{classId}/members with Authorization: Bearer {token} and JSON body:
   { "userId": "{learnerId}" }
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.addMember().
4. ClassService retrieves class by ID and validates it exists and is active.
5. ClassService validates user exists and has LEARNER role.
6. ClassService checks current enrollment vs maxStudents.
7. ClassService checks if learner is already an active member.
8. ClassService creates or restores ClassMember record with removedAt=null.
9. ClassRepository saves the class member.
10. System returns the created membership response.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- data.userId = learner UUID
- data.classId = class UUID
- data.removedAt = empty/null
Database state: ClassMember record exists with removedAt=null


---

### Test Case ID: TC_CLM_AL_002
**Test Case Description:** Class full → CLASS_FULL

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher user exists in database
- Course exists with ACTIVE status
- Class exists with maxStudents=2
- Two learners already added to class

**Test Case Procedure:**
1. Client sends POST /api/v1/classes/{classId}/members with Authorization: Bearer {token} and JSON body for new learner.
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.addMember().
4. ClassService retrieves class and checks current enrollment (2) vs maxStudents (2).
5. ClassService detects class is full and throws BusinessException with CLASS_FULL.
6. System returns the class full error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "CLASS_FULL"

---

### Test Case ID: TC_CLM_AL_003
**Test Case Description:** Inactive class → CLASS_INACTIVE

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher user exists in database
- Course exists with ACTIVE status
- Class exists with isActive=false
- Learner user exists

**Test Case Procedure:**
1. Client sends POST /api/v1/classes/{classId}/members with Authorization: Bearer {token} and JSON body.
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.addMember().
4. ClassService retrieves class and detects isActive=false.
5. ClassService throws BusinessException with CLASS_INACTIVE.
6. System returns the class inactive error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "CLASS_INACTIVE"

---

### Test Case ID: TC_CLM_AL_004
**Test Case Description:** Non-LEARNER role (TEACHER) → INVALID_MEMBER_ROLE

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher users exist in database
- Course exists with ACTIVE status
- Class exists with isActive=true

**Test Case Procedure:**
1. Client sends POST /api/v1/classes/{classId}/members with Authorization: Bearer {token} and JSON body with userId pointing to TEACHER.
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.addMember().
4. ClassService validates user role via UserRepository.
5. ClassService detects user is not a LEARNER and throws BusinessException with INVALID_MEMBER_ROLE.
6. System returns the validation error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "INVALID_MEMBER_ROLE"

---

### Test Case ID: TC_CLM_AL_005
**Test Case Description:** Already active member → MEMBER_ALREADY_EXISTS

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher user exists in database
- Course exists with ACTIVE status
- Class exists with isActive=true
- Learner already added as active member

**Test Case Procedure:**
1. Client sends POST /api/v1/classes/{classId}/members with Authorization: Bearer {token} and JSON body for existing learner.
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.addMember().
4. ClassService checks if learner is already an active member via ClassMemberRepository.
5. ClassService detects existing active membership and throws BusinessException with MEMBER_ALREADY_EXISTS.
6. System returns the validation error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "MEMBER_ALREADY_EXISTS"

---

### Test Case ID: TC_CLM_AL_006
**Test Case Description:** TEACHER token → 403 Forbidden

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course exists with ACTIVE status
- Class exists with teacherId=teacher
- Learner user exists

**Test Case Procedure:**
1. Client sends POST /api/v1/classes/{classId}/members with Authorization: Bearer {teacherToken} and JSON body.
2. AuthFilter validates token and resolves TEACHER user identity.
3. SecurityConfig denies access due to insufficient permissions (RBAC).
4. System returns the access denied error response.

**Expected Results:** HTTP 403 FORBIDDEN
Response JSON:
- success = false
- code = "FORBIDDEN"

---

### Test Case ID: TC_CLM_AL_007
**Test Case Description:** INACTIVE learner auto-promoted to ACTIVE on assignment

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher user exists in database
- Course exists with ACTIVE status
- Class exists with isActive=true
- Learner exists with INACTIVE status

**Test Case Procedure:**
1. Client sends POST /api/v1/classes/{classId}/members with Authorization: Bearer {token} and JSON body for INACTIVE learner.
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.addMember().
4. ClassService validates user exists and has LEARNER role.
5. ClassService detects user status is INACTIVE and auto-promotes to ACTIVE.
6. ClassService creates ClassMember record.
7. ClassRepository saves the class member.
8. UserRepository updates user status to ACTIVE.
9. System returns the created membership response.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- data.userId = learner UUID
Database state: learner.status = ACTIVE


---

### Test Case ID: TC_CLM_AL_009
**Test Case Description:** Eligible-learner search returns no results → empty page

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher user exists in database
- Course exists with ACTIVE status
- Class exists with isActive=true
- Learner exists but won't match search keyword

**Test Case Procedure:**
1. Client sends GET /api/v1/classes/{classId}/eligible-learners?keyword=zzz_no_match_zzz with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.getEligibleLearners().
4. ClassService searches learners by keyword via UserRepository.
5. UserRepository returns empty results.
6. System returns empty paginated response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.content.length() = 0

---

### Test Case ID: TC_CLM_AL_010
**Test Case Description:** Eligible-learner search by keyword returns matching records

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher user exists in database
- Course exists with ACTIVE status
- Class exists with isActive=true
- Learners exist: alice.smith@test.com, bob.jones@test.com

**Test Case Procedure:**
1. Client sends GET /api/v1/classes/{classId}/eligible-learners?keyword=alice with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.getEligibleLearners().
4. ClassService searches learners by keyword via UserRepository.
5. UserRepository returns matching learners.
6. System returns paginated response with matching learners.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.content.length() = 1
- data.content[0].email = "alice.smith@test.com"

---

### Test Case ID: TC_CLM_AL_011
**Test Case Description:** Assign learner to non-existing class → 404 NOT_FOUND

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Learner user exists
- Class ID does not exist in database

**Test Case Procedure:**
1. Client sends POST /api/v1/classes/{classId}/members with Authorization: Bearer {token} and JSON body.
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.addMember().
4. ClassService retrieves class by ID via ClassRepository.findByIdAndNotDeleted().
5. ClassRepository returns empty (no class found).
6. ClassService throws BusinessException with NOT_FOUND.
7. System returns the not found error response.

**Expected Results:** HTTP 404 NOT FOUND
Response JSON:
- success = false
- message = "Lớp học không tồn tại."

---

### Test Case ID: TC_CLM_AL_012
**Test Case Description:** Assign non-existing user to class → 404 NOT_FOUND

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher user exists in database
- Course exists with ACTIVE status
- Class exists with isActive=true
- User ID does not exist in database

**Test Case Procedure:**
1. Client sends POST /api/v1/classes/{classId}/members with Authorization: Bearer {token} and JSON body with non-existent userId.
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.addMember().
4. ClassService validates user existence via UserRepository.
5. UserRepository returns empty (no user found).
6. ClassService throws BusinessException with NOT_FOUND.
7. System returns the not found error response.

**Expected Results:** HTTP 404 NOT FOUND
Response JSON:
- success = false
- message = "Không tìm thấy học viên."

---

### Test Case ID: TC_CLM_AL_013
**Test Case Description:** Eligible-learners excludes SUSPENDED and PENDING_ACTIVATION accounts

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher user exists in database
- Course exists with ACTIVE status
- Class exists with isActive=true
- Learners exist with different statuses:
  - active@test.com (ACTIVE)
  - inactive@test.com (INACTIVE)
  - suspended@test.com (SUSPENDED)
  - pending@test.com (PENDING_ACTIVATION)

**Test Case Procedure:**
1. Client sends GET /api/v1/classes/{classId}/eligible-learners with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.getEligibleLearners().
4. ClassService retrieves eligible learners via UserRepository.findEligibleLearners().
5. UserRepository filters by status IN ('INACTIVE').
6. System returns paginated response with eligible learners only.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.content.length() = 2
All returned learners have status = "ACTIVE" or "INACTIVE"
No learners with status = "SUSPENDED" or "PENDING_ACTIVATION"

---

### Test Case ID: TC_CLM_AL_014
**Test Case Description:** Re-join: previously removed learner gets a restored membership; prior progress association is intact

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher user exists in database
- Course exists with ACTIVE status
- Class exists with isActive=true
- Learner exists
- ClassMember record exists with removedAt set (previously removed)

**Test Case Procedure:**
1. Client sends POST /api/v1/classes/{classId}/members with Authorization: Bearer {token} and JSON body for previously removed learner.
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.addMember().
4. ClassService finds existing ClassMember record with removedAt not null.
5. ClassService restores existing membership by clearing removedAt.
6. ClassRepository saves the restored ClassMember (no new record created).
7. System returns the created membership response.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- data.removedAt = empty/null
Database state: Exactly one ClassMember record exists with removedAt=null
No duplicate membership records created

---

## Section 7 — Remove Learner from Class Tests (DELETE /api/v1/classes/{classId}/members/{userId})

### Test Case ID: TC_CLM_RL_001
**Test Case Description:** Remove learner successfully, removedAt set

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher user exists in database
- Course exists with ACTIVE status
- Class exists with isActive=true
- Learner added as active member

**Test Case Procedure:**
1. Client sends DELETE /api/v1/classes/{classId}/members/{userId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.removeMember().
4. ClassService retrieves class and validates it exists.
5. ClassService finds ClassMember record via ClassMemberRepository.
6. ClassService updates ClassMember.removedAt to current timestamp.
7. ClassRepository saves the updated ClassMember.
8. ClassService checks if learner has other active classes; if not, sets learner status to INACTIVE.
9. System returns success response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- message = "Xóa học viên ra khỏi lớp học thành công."
Database state: 
- ClassMember.removedAt = non-null timestamp
- Active member count for class = 0
- Learner status = INACTIVE (if no other active classes)
Learner no longer appears in active members list

---

### Test Case ID: TC_CLM_RL_002
**Test Case Description:** Already removed → MEMBER_ALREADY_REMOVED

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher user exists in database
- Course exists with ACTIVE status
- Class exists with isActive=true
- Learner has ClassMember record with removedAt already set

**Test Case Procedure:**
1. Client sends DELETE /api/v1/classes/{classId}/members/{userId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.removeMember().
4. ClassService finds ClassMember record and detects removedAt is already set.
5. ClassService throws BusinessException with MEMBER_ALREADY_REMOVED.
6. System returns the validation error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "MEMBER_ALREADY_REMOVED"

---

### Test Case ID: TC_CLM_RL_003
**Test Case Description:** Not a member → MEMBER_NOT_FOUND

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher user exists in database
- Course exists with ACTIVE status
- Class exists with isActive=true
- Learner exists but is not a member of the class

**Test Case Procedure:**
1. Client sends DELETE /api/v1/classes/{classId}/members/{userId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.removeMember().
4. ClassService searches for ClassMember record via ClassMemberRepository.
5. ClassRepository returns empty (no membership record found).
6. ClassService throws BusinessException with MEMBER_NOT_FOUND.
7. System returns the validation error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "MEMBER_NOT_FOUND"

---


### Test Case ID: TC_CLM_RL_005
**Test Case Description:** Remove from non-existing class → 404 NOT_FOUND

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Learner user exists
- Class ID does not exist in database

**Test Case Procedure:**
1. Client sends DELETE /api/v1/classes/{classId}/members/{userId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.removeMember().
4. ClassService retrieves class by ID via ClassRepository.findByIdAndNotDeleted().
5. ClassRepository returns empty (no class found).
6. ClassService throws BusinessException with NOT_FOUND.
7. System returns the not found error response.

**Expected Results:** HTTP 404 NOT FOUND
Response JSON:
- success = false
- message = "Lớp học không tồn tại."

---

### Test Case ID: TC_CLM_RL_006
**Test Case Description:** TEACHER token cannot remove learner from class → 403 Forbidden

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course exists with ACTIVE status
- Class exists with teacherId=teacher
- Learner added as active member

**Test Case Procedure:**
1. Client sends DELETE /api/v1/classes/{classId}/members/{userId} with Authorization: Bearer {teacherToken}.
2. AuthFilter validates token and resolves TEACHER user identity.
3. SecurityConfig denies access due to insufficient permissions (RBAC).
4. System returns the access denied error response.

**Expected Results:** HTTP 403 FORBIDDEN
Response JSON:
- success = false
- code = "FORBIDDEN"

---

## Section 8 — Delete Class Tests (DELETE /api/v1/classes/{classId})

### Test Case ID: TC_CLM_DC_001
**Test Case Description:** Delete class successfully, deletedAt set, hidden from views

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher user exists in database
- Course exists with ACTIVE status
- Class exists with isActive=true

**Test Case Procedure:**
1. Client sends DELETE /api/v1/classes/{classId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.deleteClass().
4. ClassService retrieves class by ID via ClassRepository.findByIdAndNotDeleted().
5. ClassRepository executes database query and returns the class object.
6. ClassService performs soft delete: sets deletedAt and deletedBy via ClassRepository.save().
7. ClassRepository saves the updated class with soft delete fields.
8. System returns the delete success response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.id = class UUID
Database state: class.deletedAt ≠ null, class.deletedBy = managerId
Class not found by findByIdAndNotDeleted()

---

### Test Case ID: TC_CLM_DC_002
**Test Case Description:** Delete non-existing class → 404 NOT_FOUND

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Class ID does not exist in database

**Test Case Procedure:**
1. Client sends DELETE /api/v1/classes/{classId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.deleteClass().
4. ClassService retrieves class by ID via ClassRepository.findByIdAndNotDeleted().
5. ClassRepository returns empty (no class found).
6. ClassService throws BusinessException with NOT_FOUND.
7. System returns the not found error response.

**Expected Results:** HTTP 404 NOT FOUND
Response JSON:
- success = false

---

### Test Case ID: TC_CLM_DC_003
**Test Case Description:** TEACHER token → 403 Forbidden

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course exists with ACTIVE status
- Class exists with teacherId=teacher

**Test Case Procedure:**
1. Client sends DELETE /api/v1/classes/{classId} with Authorization: Bearer {teacherToken}.
2. AuthFilter validates token and resolves TEACHER user identity.
3. SecurityConfig denies access due to insufficient permissions (RBAC).
4. System returns the access denied error response.

**Expected Results:** HTTP 403 FORBIDDEN
Response JSON:
- success = false
- code = "FORBIDDEN"

---

### Test Case ID: TC_CLM_DC_004
**Test Case Description:** Cannot delete active class with enrolled learners → CLASS_HAS_LEARNERS

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher user exists in database
- Course exists with ACTIVE status
- Class exists with isActive=true
- Two learners added as active members (status ACTIVE)

**Test Case Procedure:**
1. Client sends DELETE /api/v1/classes/{classId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.deleteClass().
4. ClassService retrieves class by ID via ClassRepository.findByIdAndNotDeleted().
5. ClassRepository executes database query and returns the class object.
6. ClassService checks for active members and detects enrolled learners exist.
7. ClassService throws BusinessException with CLASS_HAS_LEARNERS.
8. System returns the validation error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "CLASS_HAS_LEARNERS"
- message = "Không thể xóa lớp học khi còn học viên."
Database state: 
- Class remains active (deletedAt = null)
- Learner statuses unchanged (ACTIVE)
- ClassMember records unchanged

---

### Test Case ID: TC_CLM_DC_005
**Test Case Description:** Delete inactive class: learner statuses unchanged (active stay active, inactive stay inactive)

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher user exists in database
- Course exists with ACTIVE status
- Class exists with isActive=false (inactive class)
- Two learners added to class:
  - One learner with ACTIVE status
  - One learner with INACTIVE status

**Test Case Procedure:**
1. Client sends DELETE /api/v1/classes/{classId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
3. ClassController calls ClassService.deleteClass().
4. ClassService retrieves class by ID via ClassRepository.findByIdAndNotDeleted().
5. ClassRepository executes database query and returns the class object.
6. ClassService performs soft delete: sets deletedAt and deletedBy via ClassRepository.save().
7. ClassRepository saves the updated class with soft delete fields.
8. ClassService detects class is inactive, so learner statuses are NOT updated.
9. System returns the delete success response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- message = "Xóa lớp học thành công."
- data.id = class UUID
Database state: 
- class.deletedAt ≠ null, class.deletedBy = managerId
- ClassMember records still intact with removedAt = null (historical data preserved)
- Active learner status unchanged (ACTIVE)
- Inactive learner status unchanged (INACTIVE)

---

### Test Case ID: TC_CLM_CC_003
**Test Case Description:** Cannot create class with end date in the past

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Teacher user exists in database with ACTIVE status
- Course exists with ACTIVE status

**Test Case Procedure:**
1. Client prepares request body with end date in the past (10 days ago):
   {
     "name": "Past Due Class",
     "description": "Test class with past end date",
     "teacherId": "{teacherId}",
     "courseId": "{courseId}",
     "maxStudents": 20,
     "startDate": "{currentDate}",
     "endDate": "{pastDate}"
   }
2. Client sends POST /api/v1/classes with Authorization: Bearer {token} and JSON body.
3. AuthFilter validates token and resolves OPERATION_MANAGER user identity.
4. ClassController calls ClassService.createClass().
5. ClassService validates request parameters including date range.
6. ClassService detects endDate (past date) < current date.
7. ClassService throws BusinessException with INVALID_DATE_RANGE.
8. System returns the validation error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "INVALID_END_DATE"

```

---
