# Course Management Integration Test Script

## Overview
This document contains detailed test scripts for all course management API endpoints in Satori Learning Support Application backend.

## Test Environment Setup

### Pre-conditions
- Test DB running via `application-test.properties`
- PostgreSQL `satori_test` database created and initialized
- Docker environment up and running
- EmailService mocked to prevent actual email sending
- CloudinaryService mocked to prevent actual file uploads

---

## Section 1 — View Course List Tests (GET /api/v1/manager/courses)

### Test Case ID: TC_CM_VL_001
**Test Case Description:** Manager view course list success with default pagination

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Courses inserted via courseRepository.save():
- "Tiếng Nhật N5" (level=N5, status=ACTIVE)
- "Tiếng Nhật N4" (level=N4, status=INACTIVE)
- "Tiếng Nhật N3" (level=N3, status=ACTIVE)

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/courses with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. CourseController calls CourseService.getCourses() with pagination params.
4. CourseService retrieves course list via CourseRepository.findAll().
5. CourseRepository executes database query and returns paginated course list.
6. System returns the paginated course list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.totalElements = 3
- data.content contains courses with levels: "N5", "N4", "N3"
- data.pageNumber = 0
- data.pageSize = 10 (default)
- data.first = true
- data.last = true (only 3 items)
All items contain required fields: id, name, jlptLevel, status, createdAt

---

### Test Case ID: TC_CM_VL_002
**Test Case Description:** Manager search by keyword

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Courses inserted via courseRepository.save():
- "Tiếng Nhật N5" (level=N5, status=ACTIVE)
- "Tiếng Nhật N4" (level=N4, status=ACTIVE)
- "Unrelated Course" (level=N3, status=ACTIVE)

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/courses?keyword=N5 with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. CourseController calls CourseService.getCourses() with keyword filter.
4. CourseService searches courses by keyword in name via CourseRepository.searchByKeyword().
5. CourseRepository executes database query with keyword search and returns matching results.
6. System returns the filtered paginated course list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.totalElements = 1
- data.content contains course with name = "Tiếng Nhật N5"
- data.pageNumber = 0
- data.pageSize = 10 (default)
- data.first = true
- data.last = true (only 1 item)

---

### Test Case ID: TC_CM_VL_003
**Test Case Description:** Manager filter by level

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Courses inserted via courseRepository.save():
- "Course A" (level=N5, status=ACTIVE)
- "Course B" (level=N5, status=INACTIVE)
- "Course C" (level=N3, status=ACTIVE)

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/courses?level=N5 with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. CourseController calls CourseService.getCourses() with level filter.
4. CourseService retrieves filtered course list via CourseRepository.findByJlptLevel().
5. CourseRepository executes database query with level filter and returns matching results.
6. System returns the filtered paginated course list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.totalElements = 2
- All items in data.content have jlptLevel = "N5"
- data.pageNumber = 0
- data.pageSize = 10 (default)
- data.first = true
- data.last = true (only 2 items)

---

### Test Case ID: TC_CM_VL_004
**Test Case Description:** Manager combined filter (keyword + level + status)

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Courses inserted via courseRepository.save():
- "Nhật N5 Cơ Bản" (level=N5, status=ACTIVE) - MATCH
- "Nhật N5 Nâng Cao" (level=N5, status=INACTIVE) - status mismatch
- "Nhật N4 Cơ Bản" (level=N4, status=ACTIVE) - level mismatch
- "Unrelated" (level=N5, status=ACTIVE) - keyword mismatch
- "N5 Grammar" (level=N5, status=ACTIVE) - keyword mismatch

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/courses?keyword=Nhật&level=N5&status=ACTIVE with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. CourseController calls CourseService.getCourses() with combined filters.
4. CourseService retrieves filtered course list via CourseRepository.findWithFilters().
5. CourseRepository executes database query with all filters and returns matching results.
6. System returns the filtered paginated course list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.totalElements = 1
- data.content contains course with name = "Nhật N5 Cơ Bản"
- data.pageNumber = 0
- data.pageSize = 10 (default)
- data.first = true
- data.last = true (only 1 item)

---

### Test Case ID: TC_CM_VL_005
**Test Case Description:** Invalid enum value for level parameter

**Pre-conditions:**
- Manager accessToken is available (from prior login)

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/courses?level=N9 with Authorization: Bearer {token}.
2. MethodArgumentTypeMismatchException thrown at controller level due to invalid enum value.
3. System returns the validation error response before reaching CourseService.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false

---

### Test Case ID: TC_CM_VL_006
**Test Case Description:** Access without token

**Pre-conditions:**
- No authentication token provided

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/courses without Authorization header.
2. AuthFilter detects missing token and returns authentication error.
3. System returns the unauthorized error response.

**Expected Results:** HTTP 401 UNAUTHORIZED
Response JSON:
- success = false

---

### Test Case ID: TC_CM_VL_007
**Test Case Description:** Learner cannot view course list

**Pre-conditions:**
- Learner accessToken is available (from prior login)

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/courses with Authorization: Bearer {learnerToken}.
2. AuthFilter validates token and resolves LEARNER user identity.
3. SecurityConfig denies access due to insufficient permissions (RBAC).
4. System returns the access denied error response.

**Expected Results:** HTTP 403 FORBIDDEN
Response JSON:
- success = false

---

## Section 2 — Create Course Tests (POST /api/v1/manager/courses)

### Test Case ID: TC_CM_CR_001
**Test Case Description:** Manager create course without thumbnail success

**Pre-conditions:**
- Manager accessToken is available (from prior login)

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/courses with Authorization: Bearer {token} and multipart data:
   - request part: {"name":"No Thumb Course","jlptLevel":"N4","description":"Desc"}
   - No thumbnail file provided
2. AuthFilter validates token and resolves MANAGER user identity.
3. CourseController calls CourseService.createCourse().
4. CourseService validates course name uniqueness via CourseRepository.findByName().
5. CourseRepository executes database query and returns empty (no existing course).
6. CourseService creates new Course entity and saves via CourseRepository.save().
7. CourseRepository saves the new course with status=INACTIVE and thumbnailUrl=null.
8. CloudinaryService.uploadImage() is NOT called.
9. System returns the created course response.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- data.name = "No Thumb Course"
- data.jlptLevel = "N4"
- data.status = "INACTIVE"
- data.thumbnailUrl = null
- data.id = non-empty UUID
- data.createdAt = non-null timestamp
- data.updatedAt = non-null timestamp
Database state: course.thumbnailUrl = null, course.status = INACTIVE, deleteAt = null
Cloudinary verification: uploadImage never called

---

### Test Case ID: TC_CM_CR_001b
**Test Case Description:** Manager create course with thumbnail success

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- CloudinaryService mocked to return URL

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/courses with Authorization: Bearer {token} and multipart data:
   - request part: {"name":"Course With Thumb","jlptLevel":"N5","description":"Test description"}
   - thumbnail file: valid JPEG image
2. AuthFilter validates token and resolves MANAGER user identity.
3. CourseController calls CourseService.createCourse().
4. CourseService validates course name uniqueness via CourseRepository.findByName().
5. CourseRepository executes database query and returns empty (no existing course).
6. CourseService validates image file via validateImageFile().
7. CloudinaryService.uploadImage() is called and returns thumbnail URL.
8. CourseService creates new Course entity and saves via CourseRepository.save().
9. CourseRepository saves the new course with thumbnail URL and status=INACTIVE.
10. System returns the created course response with Vietnamese success message.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- message = "Tạo khóa học thành công."
- data.name = "Course With Thumb"
- data.jlptLevel = "N5"
- data.status = "INACTIVE"
- data.thumbnailUrl = mock thumbnail URL
- data.id = non-empty UUID
Database state: course.thumbnailUrl = mock URL, course.status = INACTIVE
Cloudinary verification: uploadImage called exactly once

---

### Test Case ID: TC_CM_CR_002
**Test Case Description:** Empty name validation error

**Pre-conditions:**
- Manager accessToken is available (from prior login)

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/courses with Authorization: Bearer {token} and multipart data:
   - request part: {"name":"","jlptLevel":"N5","description":"Desc"}
2. Request validation fails at controller level due to blank name.
3. System returns the validation error response before reaching CourseService.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false

---

### Test Case ID: TC_CM_CR_003
**Test Case Description:** Duplicate name validation error

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Course "Duplicate Course" exists in database

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/courses with Authorization: Bearer {token} and multipart data:
   - request part: {"name":"Duplicate Course","jlptLevel":"N5","description":"Desc"}
2. AuthFilter validates token and resolves MANAGER user identity.
3. CourseController calls CourseService.createCourse().
4. CourseService validates course name uniqueness via CourseRepository.findByName().
5. CourseRepository executes database query and returns existing course.
6. CourseService detects name conflict and throws BusinessException.
7. System returns the duplicate name error response.

**Expected Results:** HTTP 400 BADREQUEST
Response JSON:
- success = false
- message = Khóa học với tên 'Duplicate Course' đã tồn tại
Database state: No new course created (still only 1 course with this name)

---

### Test Case ID: TC_CM_CR_003b
**Test Case Description:** Description too long validation error

**Pre-conditions:**
- Manager accessToken is available (from prior login)

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/courses with Authorization: Bearer {token} and multipart data:
   - request part: {"name":"Test Course","jlptLevel":"N5","description":"5001 chars..."}
2. Request validation fails at controller level due to description exceeding 5000 characters.
3. System returns the validation error response before reaching CourseService.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- message = Mô tả không được vượt quá 5000 ký tự
Database state: No course created

---

### Test Case ID: TC_CM_CR_003c
**Test Case Description:** Invalid JLPT level validation error

**Pre-conditions:**
- Manager accessToken is available (from prior login)

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/courses with Authorization: Bearer {token} and multipart data:
   - request part: {"name":"Test Course","jlptLevel":"N9","description":"Valid description"}
2. Request validation fails at controller level due to invalid enum value.
3. System returns the validation error response before reaching CourseService.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- message = JLPT level không hợp lệ
Database state: No course created

---

### Test Case ID: TC_CM_CR_004
**Test Case Description:** Cloudinary upload failure causes rollback

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- CloudinaryService mocked to throw exception

**Test Case Procedure:**
1. Mock CloudinaryService to throw exception for uploadImage().
2. Client sends POST /api/v1/manager/courses with Authorization: Bearer {token} and multipart data:
   - request part: {"name":"Test Course","jlptLevel":"N5","description":"Desc"}
   - thumbnail file: valid JPEG image
3. AuthFilter validates token and resolves MANAGER user identity.
4. CourseController calls CourseService.createCourse().
5. CourseService validates course name uniqueness via CourseRepository.findByName().
6. CourseRepository executes database query and returns empty (no existing course).
7. CourseService validates image file via validateImageFile().
8. CloudinaryService.uploadImage() throws exception.
9. @Transactional rollback occurs - course NOT saved to database.
10. System returns the upload failure error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "UPLOAD_FAILED"
- message = "Có lỗi xảy ra khi up ảnh, vui lòng thử lại sau."
Database state: Course NOT created (rollback)

---

### Test Case ID: TC_CM_CR_005
**Test Case Description:** Unsupported file format

**Pre-conditions:**
- Manager accessToken is available (from prior login)

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/courses with Authorization: Bearer {token} and multipart data:
   - request part: {"name":"Test Course","jlptLevel":"N5","description":"Desc"}
   - thumbnail file: GIF format (unsupported)
2. AuthFilter validates token and resolves MANAGER user identity.
3. CourseController calls CourseService.createCourse().
4. CourseService validates image file via validateImageFile().
5. validateImageFile() throws BusinessException with INVALID_FILE_TYPE.
6. CloudinaryService.uploadImage() is NOT called.
7. System returns the invalid file type error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "INVALID_FILE_TYPE"
- message = Chỉ cho phép các định dạng ảnh JPEG, PNG và WebP.
Cloudinary verification: uploadImage never called

---

### Test Case ID: TC_CM_CR_006
**Test Case Description:** File size exceeds limit

**Pre-conditions:**
- Manager accessToken is available (from prior login)

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/courses with Authorization: Bearer {token} and multipart data:
   - request part: {"name":"Test Course","jlptLevel":"N5","description":"Desc"}
   - thumbnail file: > 5MB size
2. AuthFilter validates token and resolves MANAGER user identity.
3. CourseController calls CourseService.createCourse().
4. CourseService validates image file via validateImageFile().
5. validateImageFile() throws BusinessException with FILE_TOO_LARGE.
6. CloudinaryService.uploadImage() is NOT called.
7. System returns the file too large error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "FILE_TOO_LARGE"
- message = File vượt quá kích thước tối đa 5MB, vui lòng chọn file khác
Cloudinary verification: uploadImage never called

---

### Test Case ID: TC_CM_CR_008
**Test Case Description:** Learner cannot create course

**Pre-conditions:**
- Learner accessToken is available (from prior login)

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/courses with Authorization: Bearer {learnerToken} and multipart data:
   - request part: {"name":"Test Course","jlptLevel":"N5","description":"Desc"}
2. AuthFilter validates token and resolves LEARNER user identity.
3. SecurityConfig denies access due to insufficient permissions (RBAC).
4. System returns the access denied error response.

**Expected Results:** HTTP 403 FORBIDDEN
Response JSON:
- success = false

---

## Section 3 — View Course Detail Tests (GET /api/v1/manager/courses/{courseId})

### Test Case ID: TC_CM_VD_001
**Test Case Description:** Manager view course detail success with comprehensive information including counts

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Course exists in database with lessons and enrolled students

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/courses/{courseId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. CourseController calls CourseService.getCourseById().
4. CourseService retrieves course by ID via CourseRepository.findByIdAndNotDeleted().
5. CourseRepository executes database query and returns the course object.
6. CourseService retrieves course's lessons via LessonRepository.findByCourseId().
7. CourseService retrieves course's student enrollment count via enrollment queries.
8. System returns the course detail response with comprehensive information.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.id = non-empty UUID
- data.name = "Comprehensive Course"
- data.jlptLevel = "N4"
- data.status = "ACTIVE"
- data.description = "Test description"
- data.thumbnailUrl = "https://example.com/course-thumb.jpg"
- data.lessonCount = 3
- data.studentCount = exists (non-null)
- data.createdAt = non-null timestamp
- data.updatedAt = non-null timestamp

---

### Test Case ID: TC_CM_VD_002
**Test Case Description:** Course not found

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Course ID does not exist in database

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/courses/{nonExistentId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. CourseController calls CourseService.getCourseById().
4. CourseService retrieves course by ID via CourseRepository.findByIdAndNotDeleted().
5. CourseRepository executes database query and returns empty (no course found).
6. CourseService throws BusinessException with NOT_FOUND.
7. System returns the not found error response.

**Expected Results:** HTTP 404 NOT FOUND
Response JSON:
- success = false
- code = "NOT_FOUND"

---

### Test Case ID: TC_CM_VD_003
**Test Case Description:** Manager cannot view soft-deleted course

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Course exists but has been soft-deleted (deletedAt set)

**Test Case Procedure:**
1. Manager soft-deletes a course via DELETE /api/v1/manager/courses/{courseId}.
2. Course is now soft-deleted with deletedAt and deletedBy set.
3. Client sends GET /api/v1/manager/courses/{courseId} with Authorization: Bearer {token}.
4. AuthFilter validates token and resolves MANAGER user identity.
5. CourseController calls CourseService.getCourseById().
6. CourseService retrieves course by ID via CourseRepository.findByIdAndNotDeleted().
7. CourseRepository executes database query and returns empty (soft-deleted course filtered out by deleted_at IS NULL).
8. CourseService throws BusinessException with NOT_FOUND.
9. System returns the not found error response.

**Expected Results:** HTTP 404 NOT FOUND
Response JSON:
- success = false
- code = "NOT_FOUND"

---

## Section 4 — Update Course Tests (PUT /api/v1/manager/courses/{courseId})

### Test Case ID: TC_CM_UP_001
**Test Case Description:** Manager update course name and description without new thumbnail (thumbnail preserved)

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Course exists in database with existing thumbnail

**Test Case Procedure:**
1. Client sends PUT /api/v1/manager/courses/{courseId} with Authorization: Bearer {token} and multipart data:
   - request part: {"name":"New Name","description":"New Desc"}
   - No thumbnail file provided
2. AuthFilter validates token and resolves MANAGER user identity.
3. CourseController calls CourseService.updateCourse().
4. CourseService retrieves course by ID via CourseRepository.findByIdAndNotDeleted().
5. CourseRepository executes database query and returns the course object.
6. CourseService updates course fields (name, description) and preserves existing thumbnail via CourseRepository.save().
7. CourseRepository saves the updated course with new values and updated timestamp.
8. CloudinaryService.uploadImage() is NOT called (no new thumbnail).
9. System returns the updated course response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.name = "New Name"
- data.description = "New Desc"
- data.thumbnailUrl = "https://old.url/thumb.jpg" (preserved)
- data.updatedAt = non-null timestamp (newer than before)
Database state: course.name = "New Name", course.description = "New Desc", course.thumbnailUrl unchanged, course.updatedAt > beforeUpdate
Cloudinary verification: uploadImage never called

---

### Test Case ID: TC_CM_UP_001b
**Test Case Description:** Manager update course with new thumbnail

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Course exists in database with existing thumbnail
- CloudinaryService mocked to return new thumbnail URL

**Test Case Procedure:**
1. Client sends PUT /api/v1/manager/courses/{courseId} with Authorization: Bearer {token} and multipart data:
   - request part: {"name":"Updated Name","description":"Updated description"}
   - thumbnail file: valid JPEG image
2. AuthFilter validates token and resolves MANAGER user identity.
3. CourseController calls CourseService.updateCourse().
4. CourseService retrieves course by ID via CourseRepository.findByIdAndNotDeleted().
5. CourseRepository executes database query and returns the course object.
6. CourseService validates image file via validateImageFile().
7. CloudinaryService.uploadImage() is called and returns new thumbnail URL.
8. CloudinaryService.extractPublicId() is called for old thumbnail cleanup.
9. CourseService updates course fields and new thumbnail URL via CourseRepository.save().
10. CourseRepository saves the updated course with new values and updated timestamp.
11. System returns the updated course response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.name = "Updated Name"
- data.description = "Updated description"
- data.thumbnailUrl = new thumbnail URL from Cloudinary
- data.updatedAt = non-null timestamp (newer than before)
Database state: course.name = "Updated Name", course.description = "Updated description", course.thumbnailUrl = new URL, course.updatedAt > beforeUpdate
Cloudinary verification: uploadImage called exactly once, extractPublicId called for old thumbnail

---

### Test Case ID: TC_CM_UP_002
**Test Case Description:** Cloudinary upload failure causes rollback

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Course exists in database with existing thumbnail
- CloudinaryService mocked to throw exception

**Test Case Procedure:**
1. Client sends PUT /api/v1/manager/courses/{courseId} with Authorization: Bearer {token} and multipart data:
   - request part: {"name":"Original","description":"Desc"}
   - thumbnail file: valid JPEG image
2. AuthFilter validates token and resolves MANAGER user identity.
3. CourseController calls CourseService.updateCourse().
4. CourseService retrieves course by ID via CourseRepository.findByIdAndNotDeleted().
5. CourseRepository executes database query and returns the course object.
6. CourseService validates image file via validateImageFile().
7. CloudinaryService.uploadImage() throws exception.
8. @Transactional rollback occurs - course NOT updated in database.
9. System returns the upload failure error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "UPLOAD_FAILED"
- message = "Có lỗi xảy ra khi up ảnh, vui lòng thử lại sau."
Database state: course.name = "Original", course.thumbnailUrl = "https://old.url/thumb.jpg" (unchanged)
Cloudinary verification: uploadImage called exactly once

---

### Test Case ID: TC_CM_UP_003
**Test Case Description:** Update thumbnail with unsupported file format

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Course exists in database with existing thumbnail

**Test Case Procedure:**
1. Client sends PUT /api/v1/manager/courses/{courseId} with Authorization: Bearer {token} and multipart data:
   - request part: {"name":"Original","description":"Desc"}
   - thumbnail file: GIF format (unsupported)
2. AuthFilter validates token and resolves MANAGER user identity.
3. CourseController calls CourseService.updateCourse().
4. CourseService retrieves course by ID via CourseRepository.findByIdAndNotDeleted().
5. CourseRepository executes database query and returns the course object.
6. CourseService validates image file via validateImageFile().
7. validateImageFile() throws BusinessException with INVALID_FILE_TYPE.
8. CloudinaryService.uploadImage() is NOT called.
9. System returns the invalid file type error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "INVALID_FILE_TYPE"
- message = Chỉ cho phép các định dạng ảnh JPEG, PNG và WebP.
Database state: course.thumbnailUrl = "https://old.url/thumb.jpg" (unchanged)
Cloudinary verification: uploadImage never called

---

### Test Case ID: TC_CM_UP_004
**Test Case Description:** Update thumbnail with file exceeding 5MB

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Course exists in database with existing thumbnail

**Test Case Procedure:**
1. Client sends PUT /api/v1/manager/courses/{courseId} with Authorization: Bearer {token} and multipart data:
   - request part: {"name":"Original","description":"Desc"}
   - thumbnail file: > 5MB size
2. AuthFilter validates token and resolves MANAGER user identity.
3. CourseController calls CourseService.updateCourse().
4. CourseService retrieves course by ID via CourseRepository.findByIdAndNotDeleted().
5. CourseRepository executes database query and returns the course object.
6. CourseService validates image file via validateImageFile().
7. validateImageFile() throws BusinessException with FILE_TOO_LARGE.
8. CloudinaryService.uploadImage() is NOT called.
9. System returns the file too large error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "FILE_TOO_LARGE"
- message = "File vượt quá kích thước tối đa 5MB, vui lòng chọn file khác"
Database state: course.thumbnailUrl = "https://old.url/thumb.jpg" (unchanged)
Cloudinary verification: uploadImage never called

---

### Test Case ID: TC_CM_UP_005
**Test Case Description:** Cloudinary deleteImage failure is ignored, update succeeds with new thumbnail

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Course exists in database with existing thumbnail
- CloudinaryService mocked to return new thumbnail URL and throw exception on deleteImage

**Test Case Procedure:**
1. Client sends PUT /api/v1/manager/courses/{courseId} with Authorization: Bearer {token} and multipart data:
   - request part: {"name":"Original","description":"Desc"}
   - thumbnail file: valid JPEG image
2. AuthFilter validates token and resolves MANAGER user identity.
3. CourseController calls CourseService.updateCourse().
4. CourseService retrieves course by ID via CourseRepository.findByIdAndNotDeleted().
5. CourseRepository executes database query and returns the course object.
6. CourseService validates image file via validateImageFile().
7. CloudinaryService.uploadImage() is called and returns new thumbnail URL.
8. CloudinaryService.extractPublicId() is called for old thumbnail cleanup.
9. CloudinaryService.deleteImage() throws exception but is silently ignored.
10. CourseService updates course fields and new thumbnail URL via CourseRepository.save().
11. CourseRepository saves the updated course with new values and updated timestamp.
12. System returns the updated course response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.name = "Original"
- data.description = "Desc"
- data.thumbnailUrl = new thumbnail URL from Cloudinary
- data.updatedAt = non-null timestamp (newer than before)
Database state: course.thumbnailUrl = new URL, course.updatedAt > beforeUpdate
Cloudinary verification: uploadImage called exactly once, extractPublicId called for old thumbnail, deleteImage called but exception ignored

---

### Test Case ID: TC_CM_UP_006
**Test Case Description:** Update with empty name validation error

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Course exists in database

**Test Case Procedure:**
1. Client sends PUT /api/v1/manager/courses/{courseId} with Authorization: Bearer {token} and multipart data:
   - request part: {"name":"","description":"Desc"}
2. Request validation fails at controller level due to blank name.
3. System returns the validation error response before reaching CourseService.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
Database state: course.name unchanged (original value preserved)

---

### Test Case ID: TC_CM_UP_007
**Test Case Description:** Update non-existent course returns not found

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Course ID does not exist in database

**Test Case Procedure:**
1. Client sends PUT /api/v1/manager/courses/{nonExistentId} with Authorization: Bearer {token} and multipart data:
   - request part: {"name":"Test Name","description":"Desc"}
2. AuthFilter validates token and resolves MANAGER user identity.
3. CourseController calls CourseService.updateCourse().
4. CourseService retrieves course by ID via CourseRepository.findByIdAndNotDeleted().
5. CourseRepository executes database query and returns empty (no course found).
6. CourseService throws BusinessException with NOT_FOUND.
7. System returns the not found error response.

**Expected Results:** HTTP 404 NOT FOUND
Response JSON:
- success = false

---

## Section 5 — Delete Course Tests (DELETE /api/v1/manager/courses/{courseId})

### Test Case ID: TC_CM_DEL_001
**Test Case Description:** Manager delete course success with soft-delete

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Course exists in database

**Test Case Procedure:**
1. Client sends DELETE /api/v1/manager/courses/{courseId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. CourseController calls CourseService.deleteCourse().
4. CourseService retrieves course by ID via CourseRepository.findByIdAndNotDeleted().
5. CourseRepository executes database query and returns the course object.
6. CourseService performs soft delete: sets deletedAt and deletedBy via CourseRepository.save().
7. CourseRepository saves the updated course with soft delete fields.
8. System returns the delete success response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- status = DELETED
Database state: course.deletedAt ≠ null, course.deletedBy = managerId

---

### Test Case ID: TC_CM_DEL_002
**Test Case Description:** Soft delete course - lessons remain with deleted_at = null (no cascade)

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Course exists in database with associated lessons

**Test Case Procedure:**
1. Client sends DELETE /api/v1/manager/courses/{courseId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. CourseController calls CourseService.deleteCourse().
4. CourseService retrieves course by ID via CourseRepository.findByIdAndNotDeleted().
5. CourseRepository executes database query and returns the course object.
6. CourseService performs soft delete: sets deletedAt and deletedBy via CourseRepository.save().
7. CourseRepository saves the updated course with soft delete fields.
8. System returns the delete success response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
Database state: course.deletedAt ≠ null, course.deletedBy = managerId
Lesson verification: All associated lessons still exist with deleted_at = null (no cascade delete)

---

### Test Case ID: TC_CM_DEL_003
**Test Case Description:** Delete non-existent course returns not found

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Course ID does not exist in database

**Test Case Procedure:**
1. Client sends DELETE /api/v1/manager/courses/{nonExistentId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. CourseController calls CourseService.deleteCourse().
4. CourseService retrieves course by ID via CourseRepository.findByIdAndNotDeleted().
5. CourseRepository executes database query and returns empty (no course found).
6. CourseService throws BusinessException with NOT_FOUND.
7. System returns the not found error response.

**Expected Results:** HTTP 404 NOT FOUND
Response JSON:
- success = false
- code = "NOT_FOUND"

---

### Test Case ID: TC_CM_DEL_004
**Test Case Description:** Delete already-soft-deleted course returns not found

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Course already soft-deleted (deletedAt set)

**Test Case Procedure:**
1. Client sends DELETE /api/v1/manager/courses/{deletedCourseId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. CourseController calls CourseService.deleteCourse().
4. CourseService retrieves course by ID via CourseRepository.findByIdAndNotDeleted().
5. CourseRepository executes database query and returns empty (course already deleted).
6. CourseService throws BusinessException with NOT_FOUND.
7. System returns the not found error response.

**Expected Results:** HTTP 404 NOT FOUND
Response JSON:
- success = false
- code = "NOT_FOUND"

---

### Test Case ID: TC_CM_DEL_005
**Test Case Description:** Learner cannot delete course

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Course exists in database

**Test Case Procedure:**
1. Client sends DELETE /api/v1/manager/courses/{courseId} with Authorization: Bearer {learnerToken}.
2. AuthFilter validates token and resolves LEARNER user identity.
3. SecurityConfig denies access due to insufficient permissions (RBAC).
4. System returns the access denied error response.

**Expected Results:** HTTP 403 FORBIDDEN
Response JSON:
- success = false
Database state: course.deletedAt = null


### Test Case ID: TC_CM_ST_001
**Test Case Description:** Manager activate course success

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Course exists with status=INACTIVE

**Test Case Procedure:**
1. Client sends PATCH /api/v1/manager/courses/{courseId}/activate with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. CourseController calls CourseService.activateCourse().
4. CourseService retrieves course by ID via CourseRepository.findByIdAndNotDeleted().
5. CourseRepository executes database query and returns the course object.
6. CourseService updates course status to ACTIVE via CourseRepository.save().
7. CourseRepository saves the updated course with new status.
8. System returns the activate success response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.status = "ACTIVE"
Database state: course.status = ACTIVE, course.updatedAt is not null

---

### Test Case ID: TC_CM_ST_002
**Test Case Description:** Manager deactivate ACTIVE course success

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Course exists with status=ACTIVE

**Test Case Procedure:**
1. Client sends PATCH /api/v1/manager/courses/{courseId}/deactivate with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. CourseController calls CourseService.deactivateCourse().
4. CourseService retrieves course by ID via CourseRepository.findByIdAndNotDeleted().
5. CourseRepository executes database query and returns the course object.
6. CourseService updates course status to INACTIVE via CourseRepository.save().
7. CourseRepository saves the updated course with new status.
8. System returns the deactivate success response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.status = "INACTIVE"
Database state: course.status = INACTIVE, course.updatedAt is not null

