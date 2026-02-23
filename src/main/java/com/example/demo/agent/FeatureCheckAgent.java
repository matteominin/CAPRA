package com.example.demo.agent;

import com.example.demo.model.Feature;
import com.example.demo.model.FeatureCoverage;
import com.example.demo.model.FeatureCoverageResponse;
import com.example.demo.service.ResilientLlmCaller;
import com.example.demo.repository.FeatureRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Agente che verifica la presenza delle feature attese (da MongoDB) nel documento analizzato.
 * Carica le feature dalla collection summary_features e usa l'LLM per controllare
 * quali checklist items sono soddisfatti nel testo del documento.
 */
@Service
public class FeatureCheckAgent {

    private static final Logger log = LoggerFactory.getLogger(FeatureCheckAgent.class);

    private static final String SYSTEM_PROMPT = """
            Sei un esperto di Ingegneria del Software. Ti viene fornito il testo di un documento
            di progetto SWE (tesi/relazione) e una lista di FEATURE ATTESE, ciascuna con una checklist.
            
            COMPITO:
            Per ogni feature, verifica se e in che misura e presente nel documento analizzando la checklist.
            
            Per ciascuna voce della checklist:
            - Cerca evidenza concreta nel testo del documento
            - Segna la voce come soddisfatta SOLO se trovi evidenza chiara
            
            REGOLE:
            - status = "PRESENT" se almeno 80%% delle voci checklist sono soddisfatte
            - status = "PARTIAL" se tra 30%% e 79%% delle voci checklist sono soddisfatte
            - status = "ABSENT" se meno del 30%% delle voci checklist sono soddisfatte
            - coverageScore = percentuale di voci soddisfatte (0-100, intero)
            - matchedItems = numero di voci soddisfatte
            - totalItems = numero totale di voci nella checklist
            - evidence = una o due frasi che spiegano cosa hai trovato (o cosa manca). NO comandi LaTeX.
            - NON inventare evidenze. Se non trovi qualcosa, dillo chiaramente.
            - Scrivi tutto in ITALIANO
            """;

    private final ChatClient chatClient;
    private final FeatureRepository featureRepository;

    public FeatureCheckAgent(@Qualifier("analysisChatClient") ChatClient chatClient,
                             FeatureRepository featureRepository) {
        this.chatClient = chatClient;
        this.featureRepository = featureRepository;
    }

    /**
     * Carica le feature da MongoDB e verifica la loro presenza nel documento.
     *
     * @param documentText testo completo del documento PDF
     * @return lista di copertura per ogni feature, o lista vuota se non ci sono feature in DB
     */
    public List<FeatureCoverage> checkFeatures(String documentText) {
        List<Feature> features = featureRepository.findAll();

        if (features.isEmpty()) {
            log.warn("FeatureCheckAgent: nessuna feature trovata in MongoDB, skip controllo");
            return List.of();
        }

        log.info("FeatureCheckAgent: verifico {} feature dal database", features.size());

        // Costruisci la descrizione delle feature per il prompt
        String featuresDescription = features.stream()
                .map(f -> {
                    String checklist = (f.checklist() != null && !f.checklist().isEmpty())
                            ? f.checklist().stream()
                                .map(item -> "    - " + item)
                                .collect(Collectors.joining("\n"))
                            : "    (nessuna checklist)";
                    return "FEATURE: %s\nDescrizione: %s\nChecklist:\n%s".formatted(
                            f.feature(), f.description(), checklist);
                })
                .collect(Collectors.joining("\n\n"));

        try {
            FeatureCoverageResponse response = ResilientLlmCaller.callEntity(
                    chatClient, SYSTEM_PROMPT,
                    """
                            Analizza il seguente documento e verifica la presenza di ciascuna feature.
                            
                            DOCUMENTO:
                            ===BEGIN===
                            %s
                            ===END===
                            
                            FEATURE DA VERIFICARE:
                            %s
                            
                            Per ogni feature, restituisci featureName (esattamente come fornito),
                            status, coverageScore, evidence, matchedItems e totalItems.
                            Per il campo "category" usa una di: Requisiti, Architettura, Testing, Design, Documentazione.
                            """.formatted(truncate(documentText, 80000), featuresDescription),
                    FeatureCoverageResponse.class, "FeatureCheckAgent");

            if (response != null && response.features() != null) {
                long present = response.features().stream()
                        .filter(f -> f.status() == FeatureCoverage.FeatureStatus.PRESENT).count();
                long partial = response.features().stream()
                        .filter(f -> f.status() == FeatureCoverage.FeatureStatus.PARTIAL).count();
                long absent = response.features().stream()
                        .filter(f -> f.status() == FeatureCoverage.FeatureStatus.ABSENT).count();

                log.info("FeatureCheckAgent: risultato — {} presenti, {} parziali, {} assenti",
                        present, partial, absent);
                return response.features();
            }

            log.warn("FeatureCheckAgent: risposta nulla dall'LLM");
            return List.of();

        } catch (Exception e) {
            log.error("FeatureCheckAgent: errore durante il controllo delle feature", e);
            return List.of();
        }
    }

    private String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "\n[... troncato ...]";
    }
}
