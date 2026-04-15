package com.fu.conversation.controller;

import com.fu.common.exception.BusinessException;
import com.fu.common.response.ApiResponse;
import com.fu.conversation.service.CurriculumChatService;
import com.fu.conversation.dto.request.SendChatMessageRequest;
import com.fu.conversation.dto.request.StartChatSessionRequest;
import com.fu.conversation.dto.response.ChatHistoryItemResponse;
import com.fu.conversation.dto.response.ChatMessageResponse;
import com.fu.conversation.dto.response.ChatSessionResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

/**
 * REST Controller: RAG Chatbot hỏi đáp nội dung giáo trình.
 * Base path: /api/v1/learner/curriculum-chat
 *
 * Endpoints:
 *   GET    /sessions                     → Danh sách phiên chat
 *   POST   /sessions                     → Tạo phiên chat mới
 *   POST   /sessions/{id}/messages       → Gửi tin nhắn (chat)
 *   GET    /sessions/{id}/messages       → Lấy lịch sử chat
 *   POST   /sessions/{id}/reopen        → Mở lại phiên chat đã đóng
 *   DELETE /sessions/{id}                → Đóng phiên chat
 */
@RestController
@RequestMapping("/api/v1/learner/curriculum-chat")
@RequiredArgsConstructor
@PreAuthorize("hasRole('LEARNER')")
@Slf4j
public class CurriculumChatController {

    private final CurriculumChatService chatService;

    /**
     * Tạo phiên chat hỏi đáp giáo trình mới.
     */
    @PostMapping("/sessions")
    public ResponseEntity<ApiResponse<ChatSessionResponse>> createSession(
            @Valid @RequestBody StartChatSessionRequest request,
            @AuthenticationPrincipal(expression = "id") UUID userId) {

        ChatSessionResponse session = chatService.createSession(
                userId,
                request.getCourseId(),
                request.getLessonId(),
                request.getJlptLevel(),
                request.getTitle()
        );
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Tạo phiên chat thành công.", session));
    }

    /**
     * Gửi tin nhắn và nhận câu trả lời từ AI.
     * AI sẽ tìm kiếm nội dung giáo trình liên quan và trả lời dựa trên đó.
     */
    @PostMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<ApiResponse<ChatMessageResponse>> sendMessage(
            @PathVariable UUID sessionId,
            @Valid @RequestBody SendChatMessageRequest request,
            @AuthenticationPrincipal(expression = "id") UUID userId) {

        ChatMessageResponse response = chatService.chat(sessionId, userId, request.getMessage());
        return ResponseEntity.ok(ApiResponse.success("Trả lời thành công.", response));
    }

    /**
     * Gửi tin nhắn và nhận câu trả lời từ AI qua SSE (streaming).
     *
     * <p>Khác với {@link #sendMessage}: trả về {@code text/event-stream}, tokens hiển thị
     * ngay khi AI tạo ra. Endpoint cũ {@code /messages} vẫn hoạt động bình thường.
     *
     * <p>SSE event types:
     * <ul>
     *   <li>{@code token}  — chunk văn bản từ AI, field {@code content}.</li>
     *   <li>{@code done}   — kết thúc stream; field {@code messageId}, {@code sources},
     *                        {@code noContextFound}, {@code timestamp}.</li>
     *   <li>{@code error}  — lỗi không phục hồi; field {@code message} (tiếng Việt).</li>
     * </ul>
     *
     * <p>Frontend phải dùng {@code fetch()} + {@code ReadableStream} (không dùng
     * {@code EventSource} vì đây là POST).
     */
    @PostMapping(value = "/sessions/{sessionId}/messages/stream",
                 produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMessage(
            @PathVariable UUID sessionId,
            @Valid @RequestBody SendChatMessageRequest request,
            @AuthenticationPrincipal(expression = "id") UUID userId) {

        return chatService.streamChat(sessionId, userId, request.getMessage());
    }

    /**
     * Lấy lịch sử tin nhắn của phiên chat (phân trang, thứ tự thời gian).
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<ApiResponse<List<ChatHistoryItemResponse>>> getHistory(
            @PathVariable UUID sessionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @AuthenticationPrincipal(expression = "id") UUID userId) {

        if (page < 0) {
            throw new BusinessException("INVALID_PAGE_NUMBER", "Số trang phải lớn hơn hoặc bằng 0.");
        }
        if (size <= 0) {
            throw new BusinessException("INVALID_PAGE_SIZE", "Kích thước trang phải lớn hơn 0.");
        }
        if (size > 100) size = 100; // Giới hạn tối đa 100 tin nhắn mỗi trang
        List<ChatHistoryItemResponse> history = chatService.getHistory(sessionId, userId, page, size);
        return ResponseEntity.ok(ApiResponse.success("Lấy lịch sử chat thành công.", history));
    }

    /**
     * Danh sách tất cả phiên chat giáo trình của học viên (phân trang, mới nhất trước).
     */
    @GetMapping("/sessions")
    public ResponseEntity<ApiResponse<Page<ChatSessionResponse>>> listSessions(
            @AuthenticationPrincipal(expression = "id") UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        if (page < 0) {
            throw new BusinessException("INVALID_PAGE_NUMBER", "Số trang phải lớn hơn hoặc bằng 0.");
        }
        if (size <= 0) {
            throw new BusinessException("INVALID_PAGE_SIZE", "Kích thước trang phải lớn hơn 0.");
        }
        if (size > 50) size = 50;
        Page<ChatSessionResponse> sessions = chatService.listSessions(userId, page, size);
        return ResponseEntity.ok(ApiResponse.success("Danh sách phiên chat.", sessions));
    }

    /**
     * Mở lại phiên chat đã đóng để tiếp tục hội thoại.
     */
    @PostMapping("/sessions/{sessionId}/reopen")
    public ResponseEntity<ApiResponse<ChatSessionResponse>> reopenSession(
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal(expression = "id") UUID userId) {

        ChatSessionResponse session = chatService.reopenSession(sessionId, userId);
        return ResponseEntity.ok(ApiResponse.success("Đã mở lại phiên chat.", session));
    }

    /**
     * Đóng phiên chat (đặt status = COMPLETED).
     */
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<ApiResponse<Void>> closeSession(
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal(expression = "id") UUID userId) {

        chatService.closeSession(sessionId, userId);
        return ResponseEntity.ok(ApiResponse.success("Đã đóng phiên chat.", null));
    }
}
