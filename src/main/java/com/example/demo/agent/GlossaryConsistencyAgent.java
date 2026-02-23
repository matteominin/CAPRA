package com.example.demo.agent;

import com.example.demo.model.GlossaryIssue;
import com.example.demo.model.GlossaryResponse;
import com.example.demo.service.ResilientLlmCaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Agente che rileva incoerenze terminologiche nel documento:
 * - Stessa entità chiamata con nomi diversi (es. "Utente"/"User"/"Cliente")
 * - Termini tecnici usati in modo intercambiabile (es. "prenotazione"/"reservation")
 * - Termini tecnici mai definiti nel glossario
 */
@Service
public class GlossaryConsistencyAgent {

    private static final Logger log = LoggerFactory.getLogger(GlossaryConsistencyAgent.class);

    private static final String SYSTEM_PROMPT = """
            Sei un esperto di Ingegneria del Software specializzato in qualita della documentazione.
            
            COMPITO:
            Analizza il documento e identifica INCOERENZE TERMINOLOGICHE:
            
            1. SINONIMI INVOLONTARI: la stessa entita/concetto viene chiamata con nomi diversi
               in punti diversi del documento.
               Esempio: "Utente" in sezione 2, "User" in sezione 4, "Cliente" in sezione 6
            
            2. TERMINI TECNICI MAI DEFINITI: parole tecniche usate senza spiegazione
               (soprattutto se il documento NON ha un glossario)
            
            3. ACRONIMI INCOERENTI: stessa sigla con significati diversi, o sigle mai espanse
            
            4. ITALIANO/INGLESE MISTO: uso incoerente di termini italiani e inglesi
               per lo stesso concetto (es. "prenotazione" vs "reservation")
            
            REGOLE:
            - Segnala SOLO incoerenze REALI che hai trovato nel testo
            - Per ogni gruppo, elenca TUTTE le varianti usate
            - severity = "MAJOR" se l'incoerenza puo causare fraintendimenti reali
            - severity = "MINOR" se e' solo una questione di stile
            - suggestion: indica quale termine dovrebbe essere usato uniformemente e perche
            - Scrivi in ITALIANO
            - NON inventare incoerenze: se il documento e' terminologicamente coerente, restituisci lista vuota
            - Massimo 10 issue: segnala solo le piu significative
            """;

    private final ChatClient chatClient;

    public GlossaryConsistencyAgent(@Qualifier("analysisChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * Analizza il documento per trovare incoerenze terminologiche.
     *
     * @param documentText testo completo del documento
     * @return lista di problemi di glossario
     */
    public List<GlossaryIssue> analyze(String documentText) {
        log.info("GlossaryConsistencyAgent: analisi terminologica in corso...");

        try {
            GlossaryResponse response = ResilientLlmCaller.callEntity(
                    chatClient, SYSTEM_PROMPT,
                    """
                            Analizza il seguente documento di Ingegneria del Software
                            e identifica tutte le incoerenze terminologiche.
                            
                            DOCUMENTO:
                            ===BEGIN===
                            %s
                            ===END===
                            """.formatted(truncate(documentText, 80000)),
                    GlossaryResponse.class, "GlossaryConsistencyAgent");

            if (response != null && response.issues() != null) {
                long major = response.issues().stream()
                        .filter(i -> "MAJOR".equalsIgnoreCase(i.severity())).count();
                log.info("GlossaryConsistencyAgent: {} incoerenze trovate ({} MAJOR, {} MINOR)",
                        response.issues().size(), major, response.issues().size() - major);
                return response.issues();
            }

            log.warn("GlossaryConsistencyAgent: risposta nulla dall'LLM");
            return List.of();

        } catch (Exception e) {
            log.error("GlossaryConsistencyAgent: errore durante l'analisi", e);
            return List.of();
        }
    }

    private String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "\n[... troncato ...]";
    }
}
