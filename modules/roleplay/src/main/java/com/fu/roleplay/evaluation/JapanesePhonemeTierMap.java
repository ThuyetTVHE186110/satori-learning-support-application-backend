package com.fu.roleplay.evaluation;

import java.util.Map;

/**
 * Static IPA → tier mapping for Japanese L2 pronunciation feedback prioritisation.
 *
 * <p>Per {@code PRONUNCIATION_FEEDBACK_PLAN.md}, every flagged phoneme is classified
 * by <b>functional load</b> (Levis 2005, 2020 — Intelligibility Principle):
 * features causing minimal-pair confusion outrank features that merely sound foreign.
 *
 * <p><b>Tier 1 — Mora / durational</b> (highest FL): handled at the <i>word</i> level
 * via Azure {@code errorType} (Omission/Insertion typically signals dropped sokuon
 * or shortened long vowel). No single IPA segment encodes "long vowel" or "geminate"
 * — they're durational properties of an entire mora. So Tier 1 entries here are
 * empty by design; the aggregator infers Tier 1 from word-level errorType.
 *
 * <p><b>Tier 2 — L1-difficult segments</b> (medium FL): listed below. ~25 entries.
 * Drill text is Vietnamese-only (matches accuracy feedback convention) with IPA
 * + kana retained for technical unambiguity.
 *
 * <p><b>Tier 3 — Pitch / vowel devoicing</b>: out of scope in v1 (Azure ja-JP cannot
 * detect them reliably). No entries.
 *
 * <p><b>Why fixed strings, not LLM-generated drills</b>: phonetic articulation
 * advice that is wrong is worse than no advice. Pedagogy text is sourced from
 * established L2 Japanese teaching references and reviewed by a human; LLM
 * generation here would risk hallucinated articulation instructions.
 */
public final class JapanesePhonemeTierMap {

    public enum Tier {
        TIER1_MORA_DURATION(1.5),
        TIER2_L1_DIFFICULT_SEGMENT(1.0),
        UNTIERED(0.7);

        private final double weight;
        Tier(double weight) { this.weight = weight; }
        public double weight() { return weight; }
    }

    public record TierEntry(Tier tier, String label, String drill) {}

    /**
     * Tier 2 IPA → entry. Keys are Azure phoneme tokens (typically X-SAMPA-like
     * or simplified IPA). Multiple keys may map to the same entry where Azure
     * uses different romanisations.
     */
    private static final Map<String, TierEntry> TIER2_MAP = Map.ofEntries(
            // ── つ /tsu/ — alveolar affricate, no English equivalent ──
            Map.entry("ts", new TierEntry(Tier.TIER2_L1_DIFFICULT_SEGMENT,
                    "つ /tsu/ — phụ âm tắc-xát",
                    "Đặt lưỡi sát răng cửa trên, thở mạnh ra để phát âm 'ts' liền mạch — không tách thành 'tu' hoặc 'su'.")),
            Map.entry("ts_h", new TierEntry(Tier.TIER2_L1_DIFFICULT_SEGMENT,
                    "つ /tsu/ — phụ âm tắc-xát",
                    "Đặt lưỡi sát răng cửa trên, thở mạnh ra để phát âm 'ts' liền mạch — không tách thành 'tu' hoặc 'su'.")),

            // ── ら り る れ ろ — alveolar tap [ɾ] ──
            Map.entry("4", new TierEntry(Tier.TIER2_L1_DIFFICULT_SEGMENT,
                    "ら hàng — âm rung nhẹ [ɾ]",
                    "Đầu lưỡi chạm nhẹ một lần vào lợi trên rồi rời ra — không phải 'r' tiếng Anh, cũng không phải 'l'.")),
            Map.entry("r", new TierEntry(Tier.TIER2_L1_DIFFICULT_SEGMENT,
                    "ら hàng — âm rung nhẹ [ɾ]",
                    "Đầu lưỡi chạm nhẹ một lần vào lợi trên rồi rời ra — không phải 'r' tiếng Anh, cũng không phải 'l'.")),

            // ── ふ /ɸu/ — voiceless bilabial fricative ──
            Map.entry("p\\", new TierEntry(Tier.TIER2_L1_DIFFICULT_SEGMENT,
                    "ふ /ɸu/ — âm xát hai môi",
                    "Khép hờ hai môi như đang thổi nến, đẩy hơi ra giữa môi — không dùng răng chạm môi như 'f' tiếng Việt.")),
            Map.entry("f", new TierEntry(Tier.TIER2_L1_DIFFICULT_SEGMENT,
                    "ふ /ɸu/ — âm xát hai môi",
                    "Khép hờ hai môi như đang thổi nến, đẩy hơi ra giữa môi — không dùng răng chạm môi như 'f' tiếng Việt.")),

            // ── し /ɕi/, ち /tɕi/, じ /dʑi/ palatal series ──
            Map.entry("s\\", new TierEntry(Tier.TIER2_L1_DIFFICULT_SEGMENT,
                    "し /ɕi/ — xát vòm cứng",
                    "Lưỡi nâng lên gần vòm cứng, hơi nhẹ hơn 'sh' tiếng Anh; không bè rộng môi.")),
            Map.entry("S", new TierEntry(Tier.TIER2_L1_DIFFICULT_SEGMENT,
                    "し /ɕi/ — xát vòm cứng",
                    "Lưỡi nâng lên gần vòm cứng, hơi nhẹ hơn 'sh' tiếng Anh; không bè rộng môi.")),
            Map.entry("ts\\", new TierEntry(Tier.TIER2_L1_DIFFICULT_SEGMENT,
                    "ち /tɕi/ — tắc-xát vòm cứng",
                    "Bắt đầu bằng tiếp xúc lưỡi-vòm cứng rồi mở thoát hơi — nhẹ và cao hơn 'ch' tiếng Việt.")),
            Map.entry("tS", new TierEntry(Tier.TIER2_L1_DIFFICULT_SEGMENT,
                    "ち /tɕi/ — tắc-xát vòm cứng",
                    "Bắt đầu bằng tiếp xúc lưỡi-vòm cứng rồi mở thoát hơi — nhẹ và cao hơn 'ch' tiếng Việt.")),
            Map.entry("dz\\", new TierEntry(Tier.TIER2_L1_DIFFICULT_SEGMENT,
                    "じ /dʑi/ — tắc-xát hữu thanh vòm cứng",
                    "Như ち nhưng có rung dây thanh; lưỡi cùng vị trí, thoát hơi nhẹ hơn 'j' tiếng Anh.")),
            Map.entry("dZ", new TierEntry(Tier.TIER2_L1_DIFFICULT_SEGMENT,
                    "じ /dʑi/ — tắc-xát hữu thanh vòm cứng",
                    "Như ち nhưng có rung dây thanh; lưỡi cùng vị trí, thoát hơi nhẹ hơn 'j' tiếng Anh.")),

            // ── Yōon palatalised: きゃ きゅ きょ, にゃ, ひゃ, みゃ, りゃ ──
            Map.entry("j", new TierEntry(Tier.TIER2_L1_DIFFICULT_SEGMENT,
                    "Yōon (きゃ/きゅ/きょ, りょ…) — mora đơn có vòm cứng",
                    "Phát âm như MỘT mora liền mạch (ví dụ りょ là 1 nhịp, không phải 'ri-o'); thân lưỡi nâng lên trong suốt mora.")),

            // ── /N/ moraic nasal (ん) — duration-bearing, often shortened by L2 ──
            Map.entry("N", new TierEntry(Tier.TIER1_MORA_DURATION,
                    "ん — nhịp mũi đầy đủ",
                    "Giữ âm mũi ん đúng 1 nhịp (mora), không nuốt vội; vị trí lưỡi điều chỉnh theo âm sau (ngạc/lưỡi/môi).")),
            Map.entry("N\\", new TierEntry(Tier.TIER1_MORA_DURATION,
                    "ん — nhịp mũi đầy đủ",
                    "Giữ âm mũi ん đúng 1 nhịp (mora), không nuốt vội; vị trí lưỡi điều chỉnh theo âm sau (ngạc/lưỡi/môi)."))
    );

    private JapanesePhonemeTierMap() {}

    /**
     * Look up tier classification for a phoneme. Returns {@link Tier#UNTIERED} entry
     * with surface IPA as label when no curated entry exists — caller can still rank
     * but with reduced weight, never silently drops.
     */
    public static TierEntry lookup(String ipa) {
        if (ipa == null) return defaultEntry("?");
        TierEntry entry = TIER2_MAP.get(ipa);
        if (entry != null) return entry;
        return defaultEntry(ipa);
    }

    private static TierEntry defaultEntry(String ipa) {
        return new TierEntry(
                Tier.UNTIERED,
                "/" + ipa + "/",
                "Luyện lặp âm này theo mẫu giáo viên hoặc TTS — đối chiếu nguyên âm/phụ âm với bản gốc."
        );
    }

    /**
     * Tier classification for a word-level errorType. Mora/duration errors typically
     * surface as Omission (skipped sokuon, shortened long vowel) or Insertion
     * (inserted vowel between geminates). Mispronunciation maps to Tier 2 (segmental).
     */
    public static Tier tierForErrorType(String errorType) {
        if (errorType == null) return Tier.UNTIERED;
        return switch (errorType) {
            case "Omission", "Insertion" -> Tier.TIER1_MORA_DURATION;
            case "Mispronunciation" -> Tier.TIER2_L1_DIFFICULT_SEGMENT;
            default -> Tier.UNTIERED;
        };
    }

    /** Vietnamese label per error type — used in feedback bullet next to the word. */
    public static String errorTypeLabel(String errorType) {
        if (errorType == null) return "";
        return switch (errorType) {
            case "Omission" -> "thiếu âm";
            case "Insertion" -> "thừa âm";
            case "Mispronunciation" -> "phát âm sai";
            case "None" -> "";
            default -> errorType;
        };
    }
}
