package com.fu.practice.entity;

/**
 * Preset số lượng câu hỏi cho auto mode.
 * Trong explicit selection mode, itemCount được tự động tính = số item được chọn.
 */
public enum PracticePreset {
    QUICK(5),
    STANDARD(10),
    FULL(20);

    private final int itemCount;

    PracticePreset(int itemCount) {
        this.itemCount = itemCount;
    }

    public int getItemCount() {
        return itemCount;
    }
}
