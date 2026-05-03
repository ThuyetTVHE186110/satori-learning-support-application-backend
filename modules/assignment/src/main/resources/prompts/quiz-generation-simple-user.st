--- TARGET CONTENT (LESSON-SPECIFIC) ---
{context}

Teacher's instruction: {teacherPrompt}
Number of questions to generate: {questionCount}
Question types to use: {questionTypes}
Target JLPT level: {jlptLevel}

CRITICAL REMINDERS:
- EVERY question MUST have non-empty explanation (3-5 sentences in Vietnamese).
- For multiple_choice: questionText MUST be a complete sentence/question, NO ____ allowed.
- For fill_blank / context_completion: questionText MUST contain EXACTLY ONE ____.
- questionText and option text MUST use [Kanji]{lb}Reading{rb} for ALL kanji (NOT for hiragana/katakana).
- Do NOT add furigana to Japanese particles (は, を, に, で, から, etc.).
- All 4 options must be UNIQUE — no duplicates.
- ABSOLUTELY NO HTML tags — NO <ruby>, <span>, <br>, etc.
- NEVER put answer words inside questionText (anti-leakage rule).
- options field MUST be an object with keys: type, shuffle, options (array of id/text/text_reading objects).
  NEVER use map format {lb}A: {lb}...{rb}, B: {lb}...{rb}{rb} for options.

{formatInstruction}
