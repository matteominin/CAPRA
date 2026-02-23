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
 * Agente di audit della suite di test.
 * Analizza l'intero documento per identificare:
 * - Requisiti critici senza test corrispondenti (gap di copertura)
 * - Incoerenze tra principi di design dichiarati e implementazione
 * - Test inadeguati o mancanti per flussi alternativi ed errori
 * - Problemi di tracciabilità requisiti-test
 */
@Service
public class TestAuditorAgent {

    private static final Logger log = LoggerFactory.getLogger(TestAuditorAgent.class);

    private static final String SYSTEM_PROMPT = """
            Sei un auditor ISO/IEC specializzato in Software Testing e Quality Assurance.
            Stai analizzando un documento di progetto (tesi/relazione) scritto da uno studente universitario.
            Il tuo report deve essere UTILE ALLO STUDENTE per migliorare il proprio lavoro.
            
            COMPITO:
            Analizza il documento fornito concentrandoti sugli aspetti di testing e coerenza architetturale:
            1. Identifica tutti i test case e le strategie di testing descritte nel documento
            2. Mappa ogni test ai requisiti/Use Case che dovrebbe verificare
            3. Segnala requisiti critici che NON hanno test corrispondenti (gap di copertura)
            4. Verifica che i principi di design dichiarati (es. Composition over Inheritance, SOLID)
               siano coerenti con le descrizioni delle classi e del codice
            5. Controlla se i test coprono i flussi alternativi e le condizioni di errore
            
            REGOLE ANTI-ALLUCINAZIONE (CRITICHE):
            - Il campo "quote" DEVE contenere una citazione TESTUALE dal documento, copiata parola per parola.
              NON parafrasare, NON riassumere. Copia esattamente dal testo.
            - Il campo "pageReference" DEVE corrispondere alla pagina reale dove appare la citazione.
            - NON inventare test o requisiti che non esistono nel documento.
            - Se non trovi problemi, restituisci una lista vuota di issues.
            
            CATEGORIZZAZIONE:
            - Usa "Testing" come category per gap di copertura e problemi nei test.
            - Usa "Architettura" come category per incoerenze tra design dichiarato e implementazione.
            
            SEVERITA:
            - HIGH: requisiti critici (sicurezza, transazioni, gestione errori) senza test.
            - MEDIUM: gap di copertura su funzionalita importanti.
            - LOW: test mancanti su funzionalita secondarie.
            
            RACCOMANDAZIONI:
            Il campo "recommendation" DEVE contenere un consiglio CONCRETO e AZIONABILE per lo studente.
            Esempio: "Aggiungere un test con @Test testRechargeBalance_PaymentFailed() che verifichi che
            quando il pagamento fallisce, il saldo non viene modificato e viene lanciata l'eccezione appropriata."
            
            FORMATO ID: TST-001, TST-002, etc.
            
            CONFIDENCE SCORE:
            Il campo "confidenceScore" deve essere un numero tra 0.0 e 1.0 che indica quanto sei sicuro
            che il problema sia reale:
            - 0.9-1.0: certezza assoluta, evidenza inequivocabile nel testo
            - 0.7-0.89: buona fiducia, evidenza chiara ma con qualche ambiguita
            - 0.5-0.69: fiducia moderata, il problema potrebbe essere interpretato diversamente
            - sotto 0.5: NON segnalare, non sei abbastanza sicuro
            
            DEDUPLICAZIONE: NON segnalare lo stesso problema gia coperto dall'agente dei requisiti.
            Concentrati SOLO sui gap di testing e coerenza architetturale. Se un problema di requisiti
            implica anche un gap di test, segnala SOLO il gap di test.
            """;

    private final ChatClient chatClient;

    public TestAuditorAgent(@Qualifier("analysisChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * Analizza il testo completo del documento e restituisce i problemi rilevati nei test.
     *
     * @param documentText testo completo del documento PDF
     * @return risposta dell'agente con la lista di issues
     */
    public AgentResponse analyze(String documentText) {
        log.info("TestAuditorAgent: avvio analisi ({} caratteri)", documentText.length());

        try {
            IssuesResponse response = ResilientLlmCaller.callEntity(
                    chatClient, SYSTEM_PROMPT,
                    """
                            Analizza il seguente documento di Ingegneria del Software.
                            Concentrati sulla copertura dei test, la coerenza tra design e implementazione,
                            e la tracciabilità tra requisiti e test case.
                            Per ogni problema, fornisci una citazione testuale esatta dal documento.
                            
                            DOCUMENTO:
                            ---
                            %s
                            ---
                            """.formatted(documentText),
                    IssuesResponse.class, "TestAuditorAgent");

            List<AuditIssue> issues = (response != null && response.issues() != null)
                    ? response.issues()
                    : List.of();

            log.info("TestAuditorAgent: analisi completata, {} problemi trovati", issues.size());
            return new AgentResponse("TestAuditorAgent", issues);

        } catch (Exception e) {
            log.error("TestAuditorAgent: errore durante l'analisi", e);
            throw new RuntimeException("Errore nell'analisi dei test: " + e.getMessage(), e);
        }
    }
}
