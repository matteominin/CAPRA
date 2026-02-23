package com.example.demo.agent;

import com.example.demo.model.AgentResponse;
import com.example.demo.model.AuditIssue;
import com.example.demo.model.IssuesResponse;
import com.example.demo.service.ResilientLlmCaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Agente di analisi dei requisiti e casi d'uso.
 * Analizza l'intero documento per identificare:
 * - Requisiti incompleti o ambigui
 * - Contraddizioni nella logica di business
 * - Use Case senza copertura di test
 * - Incoerenze tra requisiti funzionali e non-funzionali
 */
@Service
public class RequirementsAgent {

    private static final Logger log = LoggerFactory.getLogger(RequirementsAgent.class);

    private static final String SYSTEM_PROMPT = """
            Sei un auditor ISO/IEC 25010 specializzato in Ingegneria del Software.
            Stai analizzando un documento di progetto (tesi/relazione) scritto da uno studente universitario.
            Il tuo report deve essere UTILE ALLO STUDENTE per migliorare il proprio lavoro.
            
            COMPITO:
            Analizza il documento fornito e identifica problemi nei requisiti e casi d'uso:
            1. Verifica la completezza di ogni requisito (pre-condizioni, post-condizioni, flussi alternativi)
            2. Identifica ambiguita, contraddizioni e requisiti mancanti
            3. Verifica la coerenza tra requisiti funzionali e non-funzionali
            4. Mappa i casi d'uso ai test: segnala requisiti critici senza test corrispondente
            5. Verifica che i diagrammi (se presenti) siano coerenti con le descrizioni testuali
            
            REGOLE ANTI-ALLUCINAZIONE (CRITICHE):
            - Il campo "quote" DEVE contenere una citazione TESTUALE dal documento, copiata parola per parola.
              NON parafrasare, NON riassumere. Copia esattamente dal testo.
            - Il campo "pageReference" DEVE corrispondere alla pagina reale dove appare la citazione.
              Se non sei sicuro della pagina, usa la migliore approssimazione ma NON inventare.
            - NON inventare problemi che non esistono nel testo.
            - Se non trovi problemi, restituisci una lista vuota di issues.
            
            CATEGORIZZAZIONE:
            - Usa "Requisiti" come category per problemi sui requisiti e UC.
            - Usa "Architettura" come category per problemi architetturali o di design.
            
            SEVERITA:
            - HIGH: problemi di sicurezza, transazioni e gestione errori senza copertura.
            - MEDIUM: requisiti incompleti o ambigui.
            - LOW: problemi di forma o minori.
            
            RACCOMANDAZIONI:
            Il campo "recommendation" DEVE contenere un consiglio CONCRETO e AZIONABILE per lo studente.
            Non dire genericamente "migliorare"; spiega ESATTAMENTE cosa fare.
            Esempio: "Aggiungere al flusso alternativo 3.1 i passi specifici di gestione: 1) il sistema mostra errore,
            2) l'utente viene reindirizzato alla pagina precedente, 3) il sistema logga il tentativo fallito."
            
            FORMATO ID: REQ-001, REQ-002, etc.
            
            CONFIDENCE SCORE:
            Il campo "confidenceScore" deve essere un numero tra 0.0 e 1.0 che indica quanto sei sicuro
            che il problema sia reale:
            - 0.9-1.0: certezza assoluta, evidenza inequivocabile nel testo
            - 0.7-0.89: buona fiducia, evidenza chiara ma con qualche ambiguita
            - 0.5-0.69: fiducia moderata, il problema potrebbe essere interpretato diversamente
            - sotto 0.5: NON segnalare, non sei abbastanza sicuro
            
            DEDUPLICAZIONE: NON segnalare lo stesso problema da prospettive diverse. Se un problema
            riguarda sia requisiti che test, segnalalo UNA SOLA VOLTA nella categoria piu rilevante.
            """;

    private final ChatClient chatClient;

    public RequirementsAgent(@Qualifier("analysisChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * Analizza il testo completo del documento e restituisce i problemi rilevati sui requisiti.
     *
     * @param documentText testo completo del documento PDF
     * @return risposta dell'agente con la lista di issues
     */
    public AgentResponse analyze(String documentText) {
        log.info("RequirementsAgent: avvio analisi ({} caratteri)", documentText.length());

        try {
            IssuesResponse response = ResilientLlmCaller.callEntity(
                    chatClient, SYSTEM_PROMPT,
                    """
                            Analizza il seguente documento di Ingegneria del Software.
                            Identifica tutti i problemi relativi a requisiti, casi d'uso e logica di business.
                            Per ogni problema, fornisci una citazione testuale esatta dal documento.
                            
                            DOCUMENTO:
                            ---
                            %s
                            ---
                            """.formatted(documentText),
                    IssuesResponse.class, "RequirementsAgent");

            List<AuditIssue> issues = (response != null && response.issues() != null)
                    ? response.issues()
                    : List.of();

            log.info("RequirementsAgent: analisi completata, {} problemi trovati", issues.size());
            return new AgentResponse("RequirementsAgent", issues);

        } catch (Exception e) {
            log.error("RequirementsAgent: errore durante l'analisi", e);
            throw new RuntimeException("Errore nell'analisi dei requisiti: " + e.getMessage(), e);
        }
    }
}
