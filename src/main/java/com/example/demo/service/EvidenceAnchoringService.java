package com.example.demo.service;

import com.example.demo.model.AuditIssue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Servizio di Evidence Anchoring: verifica NON-LLM che le citazioni (quote)
 * esistano realmente nel documento usando fuzzy string matching (Levenshtein normalizzato).
 * <p>
 * Questo è il singolo miglioramento più efficace contro le allucinazioni:
 * se l'LLM inventa una citazione, il fuzzy match la rileva e la scarta.
 * <p>
 * Approccio:
 * - Normalizza testo e quote (lowercase, rimuovi spazi multipli, punteggiatura)
 * - Cerca il miglior match con sliding window nel documento
 * - Se il similarity score è < soglia, l'issue viene scartata
 * - Se il match è buono, il confidence score viene aggiustato in base alla qualità del match
 */
@Service
public class EvidenceAnchoringService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceAnchoringService.class);

    /** Soglia minima di similarità per accettare una citazione (0.0-1.0). */
    private static final double MIN_SIMILARITY = 0.45;

    /** Soglia sopra la quale il confidence viene aumentato. */
    private static final double BOOST_THRESHOLD = 0.70;

    /**
     * Filtra le issue verificando che le citazioni esistano nel documento.
     * Aggiusta il confidence score in base alla qualità del match.
     *
     * @param documentText testo completo del documento
     * @param issues       issue da verificare
     * @return issue filtrate con confidence aggiornato
     */
    public List<AuditIssue> anchorEvidence(String documentText, List<AuditIssue> issues) {
        if (issues.isEmpty()) return issues;

        String normalizedDoc = normalize(documentText);
        List<AuditIssue> anchored = new ArrayList<>();
        int rejected = 0;

        for (AuditIssue issue : issues) {
            if (issue.quote() == null || issue.quote().isBlank()) {
                // No quote: penalizza il confidence ma non scartare
                anchored.add(issue.withConfidence(issue.confidenceScore() * 0.5));
                continue;
            }

            String normalizedQuote = normalize(issue.quote());
            if (normalizedQuote.length() < 15) {
                // Quote troppo corta per un match affidabile: accetta con penalità
                anchored.add(issue.withConfidence(issue.confidenceScore() * 0.7));
                continue;
            }

            double similarity = bestSlidingWindowSimilarity(normalizedDoc, normalizedQuote);

            if (similarity < MIN_SIMILARITY) {
                log.debug("EvidenceAnchoring: SCARTATA [{}] — quote non trovata (similarity={:.2f}): \"{}\"",
                        issue.id(), similarity, truncate(issue.quote(), 80));
                rejected++;
            } else {
                // Aggiusta il confidence in base al match
                double adjustedConfidence;
                if (similarity >= BOOST_THRESHOLD) {
                    // Ottimo match: boost fino a +15%
                    adjustedConfidence = Math.min(1.0, issue.confidenceScore() * (1.0 + (similarity - BOOST_THRESHOLD) * 0.5));
                } else {
                    // Match accettabile ma non perfetto: penalità proporzionale
                    double penalty = 1.0 - ((BOOST_THRESHOLD - similarity) / (BOOST_THRESHOLD - MIN_SIMILARITY)) * 0.3;
                    adjustedConfidence = issue.confidenceScore() * penalty;
                }

                log.debug("EvidenceAnchoring: OK [{}] similarity={:.2f}, confidence {:.2f} → {:.2f}",
                        issue.id(), similarity, issue.confidenceScore(), adjustedConfidence);
                anchored.add(issue.withConfidence(adjustedConfidence));
            }
        }

        log.info("EvidenceAnchoring: {}/{} issues confermate, {} scartate per citazione non trovata",
                anchored.size(), issues.size(), rejected);

        return anchored;
    }

    /**
     * Trova il miglior match di `query` dentro `text` usando una sliding window
     * con distanza di Levenshtein normalizzata.
     * Per performance, usa trigram overlap come pre-filtro.
     *
     * @return miglior similarity (0.0-1.0)
     */
    private double bestSlidingWindowSimilarity(String text, String query) {
        int queryLen = query.length();
        if (queryLen == 0) return 0.0;

        // Step 1: Prova match esatto (substring contains) — O(n)
        if (text.contains(query)) return 1.0;

        // Step 2: Trigram overlap rapido su finestre per pre-filtrare
        // Usiamo una finestra leggermente più grande della query
        int windowSize = Math.min(text.length(), (int) (queryLen * 1.5));
        int step = Math.max(1, queryLen / 4);

        double bestSimilarity = 0.0;

        for (int i = 0; i <= text.length() - Math.min(queryLen / 2, windowSize); i += step) {
            int end = Math.min(i + windowSize, text.length());
            String window = text.substring(i, end);

            // Quick trigram overlap check
            double trigramScore = trigramOverlap(window, query);
            if (trigramScore < MIN_SIMILARITY * 0.6) continue; // skip dissimilar windows

            // Calcola per finestre con buon trigram score
            double sim = normalizedLevenshteinSimilarity(window, query);
            bestSimilarity = Math.max(bestSimilarity, sim);

            if (bestSimilarity >= 0.95) break; // buono abbastanza
        }

        // Step 3: Se finestra grande non ha trovato bene, prova con finestra = queryLen
        if (bestSimilarity < BOOST_THRESHOLD && text.length() > queryLen) {
            step = Math.max(1, queryLen / 3);
            for (int i = 0; i <= text.length() - queryLen; i += step) {
                int end = Math.min(i + queryLen + queryLen / 4, text.length());
                String window = text.substring(i, end);

                double sim = normalizedLevenshteinSimilarity(window, query);
                bestSimilarity = Math.max(bestSimilarity, sim);

                if (bestSimilarity >= 0.95) break;
            }
        }

        return bestSimilarity;
    }

    /**
     * Calcola la similarità normalizzata tra due stringhe usando Levenshtein.
     * Restituisce 1.0 per stringhe identiche, 0.0 per completamente diverse.
     * Per lunghe stringhe, usa un approccio approssimato per performance.
     */
    private double normalizedLevenshteinSimilarity(String a, String b) {
        if (a.equals(b)) return 1.0;
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) return 1.0;

        // Per stringhe molto lunghe (>500 char), usa confronto a blocchi
        if (a.length() > 500 || b.length() > 500) {
            return longestCommonSubsequenceRatio(a, b);
        }

        int distance = levenshteinDistance(a, b);
        return 1.0 - ((double) distance / maxLen);
    }

    /**
     * Calcolo efficiente della distanza di Levenshtein (spazio O(min(m,n))).
     */
    private int levenshteinDistance(String a, String b) {
        if (a.length() > b.length()) { String t = a; a = b; b = t; }

        int[] prev = new int[a.length() + 1];
        int[] curr = new int[a.length() + 1];

        for (int i = 0; i <= a.length(); i++) prev[i] = i;

        for (int j = 1; j <= b.length(); j++) {
            curr[0] = j;
            for (int i = 1; i <= a.length(); i++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[i] = Math.min(Math.min(curr[i - 1] + 1, prev[i] + 1), prev[i - 1] + cost);
            }
            int[] temp = prev; prev = curr; curr = temp;
        }

        return prev[a.length()];
    }

    /**
     * Rapporto LCS per stringhe lunghe — O(n*m) ma con early exit.
     */
    private double longestCommonSubsequenceRatio(String a, String b) {
        // Usa word-level per performance
        String[] wordsA = a.split("\\s+");
        String[] wordsB = b.split("\\s+");

        int m = wordsA.length, n = wordsB.length;
        if (m == 0 || n == 0) return 0.0;

        // Limita per performance
        if (m > 200) { wordsA = java.util.Arrays.copyOf(wordsA, 200); m = 200; }
        if (n > 200) { wordsB = java.util.Arrays.copyOf(wordsB, 200); n = 200; }

        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];

        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (wordsA[i - 1].equals(wordsB[j - 1])) {
                    curr[j] = prev[j - 1] + 1;
                } else {
                    curr[j] = Math.max(prev[j], curr[j - 1]);
                }
            }
            int[] t = prev; prev = curr; curr = t;
            java.util.Arrays.fill(curr, 0);
        }

        return (double) prev[n] / Math.max(m, n);
    }

    /**
     * Trigram overlap rapido per pre-filtrare finestre candidate.
     */
    private double trigramOverlap(String a, String b) {
        if (a.length() < 3 || b.length() < 3) return 0.0;

        var trigramsA = new java.util.HashSet<String>();
        for (int i = 0; i <= a.length() - 3; i++) trigramsA.add(a.substring(i, i + 3));

        int matches = 0;
        int total = 0;
        for (int i = 0; i <= b.length() - 3; i++) {
            if (trigramsA.contains(b.substring(i, i + 3))) matches++;
            total++;
        }

        return total == 0 ? 0.0 : (double) matches / total;
    }

    /**
     * Normalizza il testo: lowercase, rimuovi punteggiatura, collassa spazi.
     */
    private String normalize(String text) {
        if (text == null) return "";
        return text.toLowerCase()
                .replaceAll("[\\p{Punct}]", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    private String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }
}
