# Class Management Test Coverage Notes

Date: 2026-03-14

Scope: class list, class detail, and class member list for Manager and Teacher roles.

## Added/Updated Integration Tests (start module)

- Class list: manager filters (keyword/teacher/course/isActive), teacher keyword+course filter, empty results message.
- Class detail: class-not-found (404), teacher access denied.
- Class members: sort order by join date, teacher access denied, class-not-found, empty results message, keyword search.

## Expected Gaps and Known Deviations

- Empty list messages may not be returned by current controller implementation.
  Spec requires:
  - MSG35: "Không có lớp học nào."
  - MSG40: "Không có học viên nào trong lớp này."
- Member list keyword search is described in spec but the endpoint does not accept a keyword parameter.
  The new integration test will fail until it is implemented.

## Files

- start/src/test/java/com/fu/clazz/ClassManagementTest.java

