--- TARGET CONTENT (LESSON-SPECIFIC) ---
{context}

Teacher's instruction: {teacherPrompt}
Number of questions to generate: {questionCount}
Question types to use: {questionTypes}
Target JLPT level: {jlptLevel}

═══════════════════════════════════════════════════════════════
QUESTION TYPE PATTERNS (JSON format same, content differs)
═══════════════════════════════════════════════════════════════

❌ INCORRECT EXAMPLE (questionText contains the answer):
{
  "questionType": "multiple_choice",
  "questionText": "わたしは[学生]{lb}がくせい{rb}です。",
  "options": [...],
  "correctAnswer": "B",
  Option B: "[学生]{lb}がくせい{rb}です"  ← IDENTICAL! Student just copies!
}

✓ CORRECT EXAMPLE (questionText is a real question):
{
  "questionType": "fill_blank",
  "questionText": "わたしは____です。",
  "options": [...],
  "correctAnswer": "B",
  Option B: "[学生]{lb}がくせい{rb}です"  ← Student must THINK to choose!
}

CRITICAL REMINDERS:
- EVERY question MUST have non-empty 'explanation' fields (1-2 sentences in Vietnamese).
- For multiple_choice questions: questionText MUST be a complete question, NO ____ allowed.
- 'questionText', option 'text' MUST use [Kanji]{lb}Reading{rb} for ALL Kanji (NOT for hiragana/katakana).
- Do NOT add furigana to Japanese particles (は, を, に, で, から, etc.).
- All 4 options must be UNIQUE - no duplicates!
- Use ++text++ for underlines, **bold** (Markdown) for emphasis.
- ABSOLUTELY NO HTML tags allowed - NO <ruby>, <span>, <br>, etc.

{formatInstruction}

