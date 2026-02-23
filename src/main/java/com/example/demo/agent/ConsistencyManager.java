package com.example.demo.agent;

import com.example.demo.model.AuditIssue;
import com.example.demo.model.VerificationResponse;
import com.example.demo.model.VerifiedIssue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Meta-agente di verifica (Verification Loop).
 * Riceve gli output di RequirementsAgent e TestAuditorAgent e verifica ogni issue
 * cercando evidenza nel testo originale del documento.
 * <p>
 * Implementa:
 * - Logica anti-allucinazione (verifica citazioni)
 * - Deduplicazione cross-agente
 * - Rinumerazione sequenziale degli ID
 */
@Service
public class ConsistencyManager {

    private static final Logger log = LoggerFactory.getLogger(ConsistencyManager.class);

    private static final String SYSTEM_PROMPT = """
            Sei un meta-auditor incaricato di VERIFICARE segnalazioni prodotte da altri auditor.
            Il tuo scopo e' ELIMINARE i falsi positivi e confermare solo i problemi reali.
            
            Ti vengono forniti:
            1. Il TESTO COMPLETO del documento originale
            2. Una lista di problemi segnalati (issues) da verificare
            
            Per OGNI issue devi:
            1. CERCARE la citazione (campo "quote") nel testo originale del documento
            2. Verificare che la citazione esista REALMENTE (anche con piccole variazioni di formattazione)
            3. Verificare che il numero di pagina sia plausibile
            4. Valutare se la segnalazione descrive un problema REALE o e' un falso positivo
            5. CONTROLLARE se ci sono issue DUPLICATE: due segnalazioni che descrivono lo
               STESSO problema dalla stessa prospettiva o da prospettive diverse. In tal caso,
               imposta verified=true SOLO per la versione piu completa e dettagliata.
            
            DECISIONI:
            - verified=true: la citazione (o una molto simile) esiste nel documento E il problema e' reale E NON e' un duplicato
            - verified=false: la citazione NON esiste, O il problema e' un falso positivo, O e' un duplicato di un'altra issue gia confermata
            
            REGOLE:
            - Se la citazione NON appare nel documento in nessuna forma, imposta verified=false
            - Se la citazione e' chiaramente inventata o una parafrasi troppo libera, imposta verified=false
            - Se il problema e' un falso positivo (l'auditor ha frainteso il testo), imposta verified=false
            - Se il pageReference e' sbagliato ma il problema e' reale, CORREGGI il pageReference e imposta verified=true
            - Se due issue descrivono lo stesso problema (es. "copertura JaCoCo insufficiente" segnalato sia come REQ che come TST),
              CONFERMANE SOLO UNA (la piu completa) e RIGETTA l'altra con verificationNote="Duplicato di [ID]"
            - Spiega SEMPRE la motivazione nel campo verificationNote
            - In caso di dubbio, sii conservativo: meglio un falso negativo che un falso positivo
            """;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public ConsistencyManager(@Qualifier("analysisChatClient") ChatClient chatClient,
                              ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Verifica, deduplica e rinumera le issue candidate.
     *
     * @param documentText    testo completo del documento
     * @param candidateIssues issues prodotte dagli agenti di analisi
     * @return lista di issues verificate, deduplicate e rinumerate
     */
    public List<AuditIssue> verify(String documentText, List<AuditIssue> candidateIssues) {
        if (candidateIssues.isEmpty()) {
            log.info("ConsistencyManager: nessuna issue da verificare");
            return List.of();
        }

        log.info("ConsistencyManager: avvio verifica di {} issues candidate", candidateIssues.size());

        try {
            String issuesJson = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(candidateIssues);

            VerificationResponse response = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user("""
                            TESTO ORIGINALE DEL DOCUMENTO:
                            ===BEGIN DOCUMENT===
                            %s
                            ===END DOCUMENT===
                            
                            ISSUES DA VERIFICARE:
                            %s
                            
                            Per ogni issue, verifica la citazione nel testo originale sopra
                            e decidi se confermare o rigettare. ATTENZIONE: identifica e rimuovi i duplicati.
                            """.formatted(documentText, issuesJson))
                    .call()
                    .entity(VerificationResponse.class);

            if (response == null || response.verifiedIssues() == null) {
                log.warn("ConsistencyManager: risposta nulla, restituisco tutte le issue come non verificate");
                return renumber(candidateIssues);
            }

            List<AuditIssue> verified = response.verifiedIssues().stream()
                    .filter(VerifiedIssue::verified)
                    .map(VerifiedIssue::toAuditIssue)
                    .toList();

            long rejected = response.verifiedIssues().stream()
                    .filter(vi -> !vi.verified())
                    .count();

            long duplicates = response.verifiedIssues().stream()
                    .filter(vi -> !vi.verified())
                    .filter(vi -> vi.verificationNote() != null
                            && vi.verificationNote().toLowerCase().contains("duplicat"))
                    .count();

            // Log dettagliato delle verifiche
            response.verifiedIssues().forEach(vi ->
                    log.debug("  {} [{}]: {} — {}",
                            vi.verified() ? "✓" : "✗",
                            vi.id(),
                            vi.verified() ? "CONFERMATO" : "RIGETTATO",
                            vi.verificationNote())
            );

            log.info("ConsistencyManager: verifica completata — {} confermate, {} rigettate (di cui {} duplicati)",
                    verified.size(), rejected, duplicates);

            // Rinumera sequenzialmente per categoria
            return renumber(verified);

        } catch (JsonProcessingException e) {
            log.error("ConsistencyManager: errore nella serializzazione delle issues", e);
            return renumber(candidateIssues);
        } catch (Exception e) {
            log.error("ConsistencyManager: errore durante la verifica", e);
            log.warn("Fallback: restituzione di tutte le {} issues candidate senza verifica", candidateIssues.size());
            return renumber(candidateIssues);
        }
    }

    /**
     * Rinumera le issue sequenzialmente per prefisso basato sulla CATEGORIA.
     * REQ per Requisiti, TST per Testing, ARCH per Architettura, ISS per altro.
     */
    private List<AuditIssue> renumber(List<AuditIssue> issues) {
        Map<String, AtomicInteger> counters = new HashMap<>();

        List<AuditIssue> renumbered = new ArrayList<>(issues.size());
        for (AuditIssue issue : issues) {
            String prefix = prefixForCategory(issue.category());
            int seq = counters.computeIfAbsent(prefix, k -> new AtomicInteger(0))
                    .incrementAndGet();
            String newId = "%s-%03d".formatted(prefix, seq);
            renumbered.add(issue.withId(newId));
        }

        log.info("ConsistencyManager: rinumerazione completata — {}",
                counters.entrySet().stream()
                        .map(e -> e.getKey() + ":" + e.getValue().get())
                        .collect(Collectors.joining(", ")));

        return renumbered;
    }

    /**
     * Determina il prefisso ID in base alla categoria dell'issue.
     */
    private String prefixForCategory(String category) {
        if (category == null || category.isBlank()) return "ISS";
        return switch (category.toLowerCase()) {
            case "requisiti" -> "REQ";
            case "testing" -> "TST";
            case "architettura" -> "ARCH";
            default -> "ISS";
        };
    }


}
