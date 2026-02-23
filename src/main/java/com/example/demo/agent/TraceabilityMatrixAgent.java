package com.example.demo.agent;

import com.example.demo.model.TraceabilityEntry;
import com.example.demo.model.TraceabilityMatrixResponse;
import com.example.demo.service.ResilientLlmCaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Agente che costruisce automaticamente una matrice di tracciabilità
 * Requisiti/UC → Design → Test, segnalando i buchi di copertura.
 * <p>
 * Questo è uno dei controlli più importanti in SWE: gli studenti
 * quasi mai producono una matrice completa.
 */
@Service
public class TraceabilityMatrixAgent {

    private static final Logger log = LoggerFactory.getLogger(TraceabilityMatrixAgent.class);

    private static final String SYSTEM_PROMPT = """
            Sei un esperto di Ingegneria del Software specializzato in tracciabilita dei requisiti.
            
            COMPITO:
            Analizza il documento e costruisci una MATRICE DI TRACCIABILITA che mappa:
            - Use Case / Requisiti → Design (classi, componenti, pattern) → Test Case
            
            Per ogni Use Case o Requisito Funzionale presente nel documento:
            1. Identifica il suo ID e nome (es. UC-1 - Registrazione utente)
            2. Cerca se nel documento esiste una descrizione di design/architettura correlata
               (es. classe, controller, DAO, sequence diagram che lo implementa)
            3. Cerca se nel documento esiste un test case che verifica quel requisito
            4. Se manca il design O il test, segnala il gap
            
            REGOLE:
            - Elenca TUTTI i Use Case / requisiti trovati nel documento, anche quelli ben coperti
            - hasDesign = true se c'e' almeno un riferimento architetturale (classe, controller, DAO, diagram)
            - hasTest = true se c'e' almeno un test case che lo copre
            - designRef: scrivi brevemente COSA implementa quel requisito (es. "ReservationController, DAO pattern")
              Se non c'e', scrivi "Nessun riferimento trovato"
            - testRef: scrivi brevemente QUALE test lo copre (es. "testReservation_Success, testReservation_InvalidDate")
              Se non c'e', scrivi "Nessun test trovato"
            - gap: descrivi in 1 frase il gap principale. Se tutto coperto, stringa VUOTA "".
            - Scrivi in ITALIANO
            - NON inventare Use Case che non esistono nel documento
            """;

    private final ChatClient chatClient;

    public TraceabilityMatrixAgent(@Qualifier("analysisChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * Analizza il documento e produce la matrice di tracciabilità.
     *
     * @param documentText testo completo del documento
     * @return lista di entry della matrice
     */
    public List<TraceabilityEntry> buildMatrix(String documentText) {
        log.info("TraceabilityMatrixAgent: costruzione matrice di tracciabilita...");

        try {
            TraceabilityMatrixResponse response = ResilientLlmCaller.callEntity(
                    chatClient, SYSTEM_PROMPT,
                    """
                            Analizza il seguente documento di Ingegneria del Software e costruisci
                            la matrice di tracciabilita completa UC/Requisiti -> Design -> Test.
                            
                            DOCUMENTO:
                            ===BEGIN===
                            %s
                            ===END===
                            """.formatted(truncate(documentText, 80000)),
                    TraceabilityMatrixResponse.class, "TraceabilityMatrixAgent");

            if (response != null && response.entries() != null) {
                long withDesign = response.entries().stream().filter(TraceabilityEntry::hasDesign).count();
                long withTest = response.entries().stream().filter(TraceabilityEntry::hasTest).count();
                long gaps = response.entries().stream()
                        .filter(e -> !e.hasDesign() || !e.hasTest())
                        .count();

                log.info("TraceabilityMatrixAgent: {} UC/requisiti trovati — {}/{} con design, {}/{} con test, {} gap",
                        response.entries().size(), withDesign, response.entries().size(),
                        withTest, response.entries().size(), gaps);
                return response.entries();
            }

            log.warn("TraceabilityMatrixAgent: risposta nulla dall'LLM");
            return List.of();

        } catch (Exception e) {
            log.error("TraceabilityMatrixAgent: errore durante la costruzione della matrice", e);
            return List.of();
        }
    }

    private String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "\n[... troncato ...]";
    }
}
