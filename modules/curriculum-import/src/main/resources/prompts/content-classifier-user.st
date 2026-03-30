Classify the following Japanese language textbook content from pages {pageStart}-{pageEnd}.

Text:
---
{content}
---

Return JSON with this EXACT structure:
{
  "contentType": "VOCABULARY" | "GRAMMAR" | "MIXED" | "CONVERSATION" | "EXERCISE" | "OTHER",
  "confidence": 0.0-1.0,
  "reasoning": "Brief explanation of classification"
}

Classification rules:
- VOCABULARY: Word lists, vocabulary tables (>70% vocabulary content)
- GRAMMAR: Grammar rules, conjugation tables, sentence patterns (>70% grammar)
- MIXED: Balanced mix of vocabulary and grammar
- CONVERSATION: Dialogue practice, conversation examples
- EXERCISE: Drills, quizzes, practice problems
- OTHER: Non-educational content (TOC, index, copyright)