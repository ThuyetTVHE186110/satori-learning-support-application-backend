# Course Management — Test Procedures
> Format: System Interaction Steps  
> Last updated: 2026-03-08

---

## Section 1 — View Course List `GET /api/v1/manager/courses`

### TC_CM_VL_001 — paginated list returned, all fields present, totalElements ≥ 3
1. Client → GET `/api/v1/manager/courses` with pagination default params and a valid MANAGER Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. CourseController → call `CourseService.getAllCourses()`.
4. CourseService → retrieve all courses from Database (deleted_at IS NULL).
5. CourseService → build the paginated response with all required fields (id, name, jlptLevel, status, createdAt).
6. System → return response.

---

### TC_CM_VL_002 — keyword=N5 → only 'Tiếng Nhật N5' returned, others excluded
1. Client → GET `/api/v1/manager/courses?keyword=N5` with a valid MANAGER Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. CourseController → call `CourseService.getAllCourses()` with keyword param.
4. CourseService → retrieve courses by matching keyword (LIKE %keyword%) and active status.
5. System → return response.

---

### TC_CM_VL_003 — level=N5 → 2 N5 courses returned, N3 course excluded
1. Client → GET `/api/v1/manager/courses?level=N5` with a valid MANAGER Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. CourseController → call `CourseService.getAllCourses()` with level param.
4. CourseService → retrieve courses by exactly matching the JlptLevel.
5. System → return response.

---

### TC_CM_VL_004 — Combined filter (keyword + level + status) returns only matching courses
1. Client → GET `/api/v1/manager/courses?keyword=Nhật&level=N5&status=ACTIVE` with a valid MANAGER Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. CourseController → call `CourseService.filterCourses()` with all 3 filter params.
4. CourseService → retrieve courses intersecting all criteria (name LIKE %keyword%, jlptLevel=N5, status=ACTIVE).
5. System → return response.

---

### TC_CM_VL_005 — level=N9 invalid enum → HTTP 400
1. Client → GET `/api/v1/manager/courses?level=N9` with a valid MANAGER Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. Spring Web → detect MethodArgumentTypeMismatchException because "N9" is not a valid enum for Course.JlptLevel.
4. System → return response.

---

### TC_CM_VL_006 — no token → HTTP 401 Unauthorized
1. Client → GET `/api/v1/manager/courses` with no Authorization header.
2. AuthFilter → detect that no token is present.
3. AuthFilter → reject the request as unauthenticated.
4. System → return response.

---

### TC_CM_VL_007 — LEARNER token → HTTP 403 Forbidden
1. Client → GET `/api/v1/manager/courses` with a valid LEARNER Bearer token.
2. AuthFilter → parse the token and resolve the user identity (role=LEARNER).
3. AuthFilter → detect that the LEARNER role is not authorized for this endpoint.
4. System → return response.

---

## Section 2 — Create Course `POST /api/v1/manager/courses`

### TC_CM_CR_001 — no thumbnail → HTTP 201, thumbnailUrl=null in DB, Cloudinary not called
1. Client → POST `/api/v1/manager/courses` (multipart format) with course data (name, jlptLevel, description) but no thumbnail file, and a valid MANAGER Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. CourseController → validate the request body and call `CourseService.createCourse()`.
4. CourseService → map the request to Entity. Thumbnail upload skipped since no file is provided.
5. CourseService → persist the course to Database (status default to INACTIVE, thumbnailUrl=null).
6. System → return response.

---

### TC_CM_CR_002 — empty name → HTTP 400 validation error
1. Client → POST `/api/v1/manager/courses` with a valid MANAGER Bearer token and an empty course name.
2. AuthFilter → parse the token and resolve the user identity.
3. CourseController → validate the request body.
4. CourseController → detect the missing name violates @NotBlank validation.
5. System → return response.

---

### TC_CM_CR_003 — duplicate name → HTTP 400/409, no new record in DB
1. Client → POST `/api/v1/manager/courses` with a valid MANAGER Bearer token and a duplicate course name.
2. AuthFilter → parse the token and resolve the user identity.
3. CourseController → validate the request body and call `CourseService.createCourse()`.
4. CourseService → check the database, detect the name already exists.
5. System → return response.

---

### TC_CM_CR_004 — Cloudinary upload fails → HTTP 400, course NOT in DB (rollback)
1. Client → POST `/api/v1/manager/courses` with course data, a valid thumbnail file, and a valid MANAGER Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. CourseController → call `CourseService.createCourse()`.
4. CloudinaryService → fails to upload the image (network error, throws BusinessException).
5. Spring Transaction → catches Exception and rolls back the database transaction (Course won't be saved).
6. System → return response.

---

### TC_CM_CR_005 — GIF format → HTTP 400 INVALID_FILE_TYPE, Cloudinary not called
1. Client → POST `/api/v1/manager/courses` with course data, a GIF thumbnail file, and a MANAGER Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. CourseController → pre-validate the multipart file.
4. FileValidator → detect unsupported format (GIF) before calling Cloudinary.
5. System → return response.

---

### TC_CM_CR_006 — file > 5MB → HTTP 400 FILE_TOO_LARGE, Cloudinary not called
1. Client → POST `/api/v1/manager/courses` with course data, an oversized thumbnail file, and a MANAGER Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. CourseController → pre-validate the multipart file.
4. FileValidator → detect the file exceeds size limit (5MB) before calling Cloudinary.
5. System → return response.

---

### TC_CM_CR_007 — cloudinary disabled (mock returns placeholder) → HTTP 201, placeholder URL in DB
1. System Configuration → assume Cloudinary is disabled locally.
2. Client → POST `/api/v1/manager/courses` with course data and thumbnail file.
3. CourseService → calls mock Cloudinary service which returns a placeholder URL immediately.
4. CourseService → persist Course with the placeholder URL.
5. System → return response.

---

### TC_CM_CR_008 — LEARNER token → HTTP 403 Forbidden
1. Client → POST `/api/v1/manager/courses` with a valid LEARNER Bearer token.
2. AuthFilter → parse the token and resolve the user identity (role=LEARNER).
3. AuthFilter → detect that the LEARNER role is not authorized for this endpoint.
4. System → return response.

---

## Section 3 — View Course Detail `GET /api/v1/manager/courses/{courseId}`

### TC_CM_VD_001 — INACTIVE course → HTTP 200, all fields present
1. Client → GET `/api/v1/manager/courses/{courseId}` with a valid MANAGER Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. CourseController → call `CourseService.getCourseById()`.
4. CourseService → retrieve the course by ID (deleted_at IS NULL). Course is visible to Manager even if status is INACTIVE.
5. System → return response.

---

### TC_CM_VD_002 — non-existent courseId → HTTP 404 not found
1. Client → GET `/api/v1/manager/courses/00000000-0000-0000-0000-000000000000` with a valid MANAGER Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. CourseController → call `CourseService.getCourseById()`.
4. CourseService → attempt to locate the course in DB.
5. CourseService → detect that no course with the given ID exists.
6. System → return response.

---

### TC_CM_VD_003 — soft-deleted course → HTTP 404 not found (deleted_at IS NULL filter)
1. Client → GET `/api/v1/manager/courses/{courseId}` with a valid MANAGER Bearer token (course is soft-deleted).
2. AuthFilter → parse the token and resolve the user identity.
3. CourseController → call `CourseService.getCourseById()`.
4. CourseService → attempt to locate active course in DB (deleted_at IS NULL).
5. CourseService → detect that the course is already deleted (not found).
6. System → return response.

---

## Section 4 — Update Course `PUT /api/v1/manager/courses/{courseId}`

### TC_CM_UP_001 — update name+description, no thumbnail → HTTP 200, thumbnailUrl preserved
1. Client → PUT `/api/v1/manager/courses/{courseId}` with new name/description but NO thumbnail file, and a MANAGER Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. CourseController → validate the request body and call `CourseService.updateCourse()`.
4. CourseService → retrieve the existing course by ID.
5. CourseService → update text fields and skip Cloudinary logic.
6. CourseService → persist the course with the old thumbnail URL.
7. System → return response.

---

### TC_CM_UP_002 — Cloudinary upload fails → HTTP 400, course unchanged (rollback)
1. Client → PUT `/api/v1/manager/courses/{courseId}` with new data and a new thumbnail file.
2. AuthFilter → parse the token and resolve the user identity.
3. CourseController → call `CourseService.updateCourse()`.
4. CloudinaryService → fails to upload the image (network error, throws BusinessException).
5. Spring Transaction → catches Exception and rolls back Database changes.
6. System → return response.

---

### TC_CM_UP_003 — GIF format → HTTP 400 INVALID_FILE_TYPE, Cloudinary not called, DB unchanged
1. Client → PUT `/api/v1/manager/courses/{courseId}` with a GIF thumbnail file.
2. AuthFilter → parse the token and resolve the user identity.
3. CourseController → pre-validate the file type.
4. FileValidator → detect unsupported format (GIF) before calling Cloudinary.
5. System → return response.

---

### TC_CM_UP_004 — file > 5MB → HTTP 400 FILE_TOO_LARGE, Cloudinary not called, DB unchanged
1. Client → PUT `/api/v1/manager/courses/{courseId}` with an oversized thumbnail file.
2. AuthFilter → parse the token and resolve the user identity.
3. CourseController → pre-validate the file size.
4. FileValidator → detect the file exceeds size limit (5MB).
5. System → return response.

---

### TC_CM_UP_005 — deleteImage() throws IOException → update succeeds, new thumbnailUrl in DB
1. Client → PUT `/api/v1/manager/courses/{courseId}` with a new valid thumbnail file.
2. AuthFilter → parse the token and resolve the user identity.
3. CourseService → retrieve existing course.
4. CloudinaryService → successfully uploads the new image and gets the new Thumbnail URL.
5. CloudinaryService → attempts to delete the old image from the cloud but throws an IOException.
6. CourseService → silently catch the delete Exception (ignored) and continue.
7. CourseService → persist the course with the new thumbnail URL.
8. System → return response.

---

### TC_CM_UP_006 — empty name → HTTP 400 validation error, course name unchanged
1. Client → PUT `/api/v1/manager/courses/{courseId}` with an empty course name.
2. AuthFilter → parse the token and resolve the user identity.
3. CourseController → validate the request body.
4. CourseController → detect the missing name violates @NotBlank constraint.
5. System → return response.

---

### TC_CM_UP_007 — non-existent courseId → HTTP 404 not found
1. Client → PUT `/api/v1/manager/courses/00000000-0000-0000-0000-000000000000` with a valid MANAGER Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. CourseController → call `CourseService.updateCourse()`.
4. CourseService → detect that no course with the given ID exists.
5. System → return response.

---

## Section 5 — Delete Course `DELETE /api/v1/manager/courses/{courseId}`

### TC_CM_DL_001 — soft delete → HTTP 200, deleted_at set, course absent from list
1. Client → DELETE `/api/v1/manager/courses/{courseId}` with a valid MANAGER Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. CourseController → call `CourseService.deleteCourse()`.
4. CourseService → retrieve the course by ID (deleted_at IS NULL).
5. CourseService → set deleted_at and deleted_by (manager UUID) on the course.
6. CourseService → persist the soft-deleted course.
7. System → return response.

---

### TC_CM_DL_002 — delete course → lessons NOT cascade-deleted (deleted_at still null)
1. Client → DELETE `/api/v1/manager/courses/{courseId}` with a valid MANAGER Bearer token.
2. CourseController → call `CourseService.deleteCourse()`.
3. CourseService → soft-delete the course.
4. CourseService → deliberately leave linked Lessons untouched (deleted_at = null).
5. System → return response.

---

### TC_CM_DL_003 — non-existent courseId → HTTP 404 not found
1. Client → DELETE `/api/v1/manager/courses/00000000-0000-0000-0000-000000000000` with a valid MANAGER Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. CourseController → call `CourseService.deleteCourse()`.
4. CourseService → attempt to locate the course in DB.
5. CourseService → detect that no course with the given ID exists.
6. System → return response.

---

### TC_CM_DL_004 — double delete → second attempt returns HTTP 404
1. Client → DELETE `/api/v1/manager/courses/{courseId}` with a valid MANAGER Bearer token (course is already soft-deleted).
2. AuthFilter → parse the token and resolve the user identity.
3. CourseController → call `CourseService.deleteCourse()`.
4. CourseService → attempt to locate active course in DB (deleted_at IS NULL).
5. CourseService → detect that the course is already deleted (not found).
6. System → return response.

---

### TC_CM_DL_005 — LEARNER token → HTTP 403, course deletedAt remains null
1. Client → DELETE `/api/v1/manager/courses/{courseId}` with a valid LEARNER Bearer token.
2. AuthFilter → parse the token and resolve the user identity (role=LEARNER).
3. AuthFilter → detect that the LEARNER role is not authorized for this endpoint.
4. System → return response.

---

## Section 6 — Activate / Deactivate `PATCH .../activate` | `PATCH .../deactivate`

### TC_CM_ST_001 — activate INACTIVE course → HTTP 200, status=ACTIVE in DB
1. Client → PATCH `/api/v1/manager/courses/{courseId}/activate` with a valid MANAGER Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. CourseController → call `CourseService.activateCourse()`.
4. CourseService → retrieve the course by ID (deleted_at IS NULL).
5. CourseService → set the course status to ACTIVE and update updatedAt.
6. System → return response.

---

### TC_CM_ST_002 — deactivate ACTIVE course → HTTP 200, status=INACTIVE in DB
1. Client → PATCH `/api/v1/manager/courses/{courseId}/deactivate` with a valid MANAGER Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. CourseController → call `CourseService.deactivateCourse()`.
4. CourseService → retrieve the course by ID (deleted_at IS NULL).
5. CourseService → set the course status to INACTIVE and update updatedAt.
6. System → return response.
