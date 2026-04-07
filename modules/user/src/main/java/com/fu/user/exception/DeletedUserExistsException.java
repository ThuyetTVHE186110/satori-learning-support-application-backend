package com.fu.user.exception;

import com.fu.user.dto.DeletedUserInfo;
import lombok.Getter;

/**
 * Exception thrown when trying to create a user with an email that belongs to a soft-deleted account.
 * Contains information about the deleted user to allow restoration.
 * This is a user-domain specific exception, not a generic common exception.
 */
@Getter
public class DeletedUserExistsException extends RuntimeException {
    
    private final DeletedUserInfo deletedUserInfo;
    
    public DeletedUserExistsException(DeletedUserInfo deletedUserInfo) {
        super("Email này đã tồn tại trong hệ thống nhưng đã bị xóa. Bạn có muốn khôi phục tài khoản này không?");
        this.deletedUserInfo = deletedUserInfo;
    }
}
