TASK: Critically evaluate the following AI extraction against the original source text.

=== ORIGINAL SOURCE TEXT (pages {pageStart}-{pageEnd}) ===
{sourceContent}
=== END SOURCE TEXT ===

=== EXTRACTED CONTENT ===
{extractedSummary}
=== END EXTRACTED CONTENT ===

TARGET JLPT LEVEL: {jlptLevel}

For EACH extracted item, evaluate these Self-RAG reflection tokens:
- [IsRelevant]: Does this item come from the source text, not from external knowledge?
- [IsSupported]: Are the meanings, readings, and explanations factually correct per the source?
- [IsUseful]: Is this item appropriate for {jlptLevel}-level learners?

Return JSON:
{
  "overallVerdict": "APPROVED" | "MOSTLY_APPROVED" | "NEEDS_IMPROVEMENT" | "REJECTED",
  "overallScore": 0.0-1.0,
  "overallReasoning": "Brief explanation of overall quality",
  "recommendsReExtraction": true/false,
  "reExtractionSuggestion": "What to fix if re-extraction needed",
  "itemReflections": [
    {
      "itemIdentifier": "日本語 or ～ている",
      "contentType": "VOCABULARY" | "GRAMMAR",
      "isRelevant": true/false,
      "isSupported": true/false,
      "isUseful": true/false,
      "score": 0.0-1.0,
      "reasoning": "Why this assessment"
    }
  ]
}

IMPORTANT GUIDELINES:
- An item is RELEVANT if it appears in the source text OR is a standard vocabulary/grammar item for this lesson (the source may be a section of a larger lesson page).
- An item is NOT SUPPORTED only if the meaning/reading is clearly WRONG or fabricated. Missing English translations, missing romaji, or incomplete explanations are NOT grounds for marking unsupported — only factual errors matter.
- An item is USEFUL if it's appropriate for the target JLPT level.
- Score: 1.0 = perfect, 0.8+ = good (minor issues only), 0.5-0.8 = needs improvement, <0.5 = reject
- Default to 0.8 for items that are clearly present in the source with correct meanings.
- Only score below 0.5 for items with WRONG Japanese text or FABRICATED meanings.