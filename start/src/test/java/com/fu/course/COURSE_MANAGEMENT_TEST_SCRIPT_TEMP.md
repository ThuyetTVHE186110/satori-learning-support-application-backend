### Test Case ID: TC_CM_ST_005
**Test Case Description:** Activate non-existent course returns not found

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Course ID does not exist in database

**Test Case Procedure:**
1. Client sends PATCH /api/v1/manager/courses/{nonExistentId}/activate with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. CourseController calls CourseService.activateCourse().
4. CourseService retrieves course by ID via CourseRepository.findByIdAndNotDeleted().
5. CourseRepository executes database query and returns empty (no course found).
6. CourseService throws BusinessException with NOT_FOUND.
   7. System returns the not found error response.

**Expected Results:** HTTP 404 NOT FOUND
Response JSON:
- success = false
- code = "NOT_FOUND"
