{context}

Teacher's instruction: {teacherPrompt}
Question count: {questionCount}
Question types: {questionTypes}
JLPT level: {jlptLevel}

Generate {questionCount} questions following all system rules. Quick checklist before output:
- [ ] Every questionText starts with a Vietnamese instruction line
- [ ] No option word appears in questionText (anti-leakage)
- [ ] Every kanji has [Kanji]{lb}Reading{rb} furigana
- [ ] Options are base form (no sentence suffixes)
- [ ] Each explanation is 3–5 plain Vietnamese sentences explaining correct AND wrong answers
- [ ] No vocabulary repeated across questions
- [ ] Difficulty distribution matches teacher's spec or default (20/40/40)

{formatInstruction}
