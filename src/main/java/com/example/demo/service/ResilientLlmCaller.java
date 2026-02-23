package com.example.demo.service;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;

/**
 * Utility per chiamate LLM resilienti con parsing JSON lenient e retry automatico.
 * <p>
 * Risolve i problemi comuni delle risposte LLM:
 * <ul>
 *   <li>Trailing commas ({@code [{"a":1},]}) — causa principale degli errori Jackson</li>
 *   <li>Commenti Java-style nel JSON</li>
 *   <li>Apici singoli al posto dei doppi</li>
 *   <li>Campi non attesi (ignoreUnknown)</li>
 * </ul>
 * <p>
 * Strategia: usa {@link BeanOutputConverter} con un {@link ObjectMapper} lenient
 * e riprova automaticamente fino a {@value #MAX_RETRIES} volte in caso di errore.
 */
public final class ResilientLlmCaller {

    private static final Logger log = LoggerFactory.getLogger(ResilientLlmCaller.class);
    private static final int MAX_RETRIES = 2;

    /** ObjectMapper lenient che tollera trailing commas, commenti e apici singoli. */
    private static final ObjectMapper LENIENT_MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
            .build()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ResilientLlmCaller() {
        // utility class — non istanziabile
    }

    /**
     * Chiama l'LLM via {@code .entity(converter)} con retry e parsing JSON lenient.
     * <p>
     * Il {@link BeanOutputConverter} aggiunge automaticamente le format instructions
     * al prompt, e parsa la risposta con l'ObjectMapper lenient configurato.
     *
     * @param chatClient   il client LLM da usare
     * @param systemPrompt il system prompt
     * @param userPrompt   il user prompt (le format instructions vengono aggiunte automaticamente)
     * @param type         la classe target per il parsing
     * @param agentName    nome dell'agente (per logging)
     * @param <T>          tipo target
     * @return la risposta parsata
     * @throws RuntimeException se tutti i tentativi falliscono
     */
    public static <T> T callEntity(ChatClient chatClient, String systemPrompt, String userPrompt,
                                    Class<T> type, String agentName) {
        var converter = new BeanOutputConverter<>(type, LENIENT_MAPPER);

        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRIES + 1; attempt++) {
            try {
                return chatClient.prompt()
                        .system(systemPrompt)
                        .user(userPrompt)
                        .call()
                        .entity(converter);
            } catch (Exception e) {
                lastError = e;
                if (attempt <= MAX_RETRIES) {
                    long delay = attempt * 2000L;
                    log.warn("{}: tentativo {}/{} fallito ({}), riprovo tra {}ms...",
                            agentName, attempt, MAX_RETRIES + 1, rootCauseMessage(e), delay);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw new RuntimeException("Errore in " + agentName + " dopo " + (MAX_RETRIES + 1)
                + " tentativi: " + lastError.getMessage(), lastError);
    }

    private static String rootCauseMessage(Exception e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        return msg != null && msg.length() > 150 ? msg.substring(0, 150) + "..." : msg;
    }
}
