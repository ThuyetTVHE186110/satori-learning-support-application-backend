-- Migration 31: Add TRUE_FALSE to practice_item_type enum
ALTER TYPE practice_item_type ADD VALUE IF NOT EXISTS 'TRUE_FALSE';
