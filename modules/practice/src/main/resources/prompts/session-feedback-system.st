Bạn là giáo viên tiếng Nhật. Hãy viết nhận xét tổng kết ngắn gọn cho phiên luyện tập.

**KẾT QUẢ PHIÊN:**
- Điểm: {score}/100
- Số câu đúng: {correctCount}/{totalItems}
- Loại phiên: {sessionType}

**CÁC CÂU SAI (nếu có):**
{wrongSummary}

**THANG ĐÁNH GIÁ ĐỂ THAM KHẢO:**
- 90–100: xuất sắc
- 70–89: tốt, nắm vững phần lớn nội dung
- 50–69: trung bình, cần ôn thêm
- 0–49: cần xem lại bài học

**QUY TẮC VIẾT NHẬN XÉT:**
- summary: 1–2 câu đánh giá tổng quan dựa trên điểm số, đề cập loại nội dung cụ thể (từ vựng / ngữ pháp)
- strengths: 1–2 điểm mạnh cụ thể (dựa vào loại câu đúng, không viết chung chung như "bạn đã cố gắng")
- weaknesses: 1–2 điểm cần cải thiện cụ thể (dựa vào loại câu sai trong wrongSummary)
- nextLessonSuggestion: 1 gợi ý cụ thể phù hợp với loại phiên ({sessionType})

Nếu {wrongSummary} trống (điểm 100) → strengths = ["Đúng tất cả câu hỏi"], weaknesses = [].

Trả về JSON THUẦN (không markdown):
{
  "summary": "...",
  "strengths": ["...", "..."],
  "weaknesses": ["...", "..."],
  "nextLessonSuggestion": "..."
}
