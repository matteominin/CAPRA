package com.example.demo.service;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;

/**
 * Utility for resilient LLM calls with lenient JSON parsing and automatic retry.
 * <p>
 * Solves common LLM response issues:
 * <ul>
 *   <li>Trailing commas ({@code [{"a":1},]}) — main cause of Jackson errors</li>
 *   <li>Java-style comments in JSON</li>
 *   <li>Single quotes instead of double quotes</li>
 *   <li>Unexpected fields (ignoreUnknown)</li>
 * </ul>
 * <p>
 * Strategy: uses {@link BeanOutputConverter} with a lenient {@link ObjectMapper}
 * and automatically retries up to {@value #MAX_RETRIES} times on error.
 */
public final class ResilientLlmCaller {

    private static final Logger log = LoggerFactory.getLogger(ResilientLlmCaller.class);
    private static final int MAX_RETRIES = 2;

    /** Lenient ObjectMapper that tolerates trailing commas, comments, and single quotes. */
    private static final ObjectMapper LENIENT_MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
            .build()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ResilientLlmCaller() {
        // utility class — not instantiable
    }

    /**
     * Calls the LLM via {@code .entity(converter)} with retry and lenient JSON parsing.
     * <p>
     * The {@link BeanOutputConverter} automatically adds format instructions
     * to the prompt and parses the response with the configured lenient ObjectMapper.
     *
     * @param chatClient   the LLM client to use
     * @param systemPrompt the system prompt
     * @param userPrompt   the user prompt (format instructions are added automatically)
     * @param type         the target class for parsing
     * @param agentName    agent name (for logging)
     * @param <T>          target type
     * @return the parsed response
     * @throws RuntimeException if all attempts fail
     */
    public static <T> T callEntity(ChatClient chatClient, String systemPrompt, String userPrompt,
                                    Class<T> type, String agentName) {
        var converter = new BeanOutputConverter<>(type, LENIENT_MAPPER);
        // Append format instructions the same way Spring AI does internally
        String fullUserPrompt = userPrompt + "\n\n" + converter.getFormat();

        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRIES + 1; attempt++) {
            try {
                ChatResponse chatResponse = chatClient.prompt()
                        .system(systemPrompt)
                        .user(fullUserPrompt)
                        .call()
                        .chatResponse();

                // ── Capture token usage ──────────────────────────────────────
                captureTokenUsage(chatResponse, agentName);

                // ── Parse response content ───────────────────────────────────
                String content = (chatResponse != null && chatResponse.getResult() != null)
                        ? chatResponse.getResult().getOutput().getText()
                        : null;
                if (content == null || content.isBlank()) {
                    throw new RuntimeException("Empty or null content in LLM response");
                }
                return converter.convert(content);
            } catch (Exception e) {
                lastError = e;
                if (attempt <= MAX_RETRIES) {
                    long delay = attempt * 2000L;
                    log.warn("{}: attempt {}/{} failed ({}), retrying in {}ms...",
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
        throw new RuntimeException("Error in " + agentName + " after " + (MAX_RETRIES + 1)
                + " attempts: " + lastError.getMessage(), lastError);
    }

    private static void captureTokenUsage(ChatResponse chatResponse, String agentName) {
        if (chatResponse == null) return;
        try {
            var metadata = chatResponse.getMetadata();
            if (metadata == null) return;
            var usage = metadata.getUsage();
            if (usage == null) return;

            long total = usage.getTotalTokens() != null ? usage.getTotalTokens().longValue() : 0L;
            if (total == 0) return;

            TokenUsageAccumulator acc = TokenUsageAccumulator.current();
            if (acc == null) return;

            // Detect provider from model name: Anthropic models contain "claude"
            String model = metadata.getModel() != null ? metadata.getModel().toLowerCase() : "";
            if (model.contains("claude")) {
                acc.addAnthropicTokens(total);
                log.debug("{}: +{} Anthropic tokens (model={})", agentName, total, metadata.getModel());
            } else {
                acc.addOpenAiTokens(total);
                log.debug("{}: +{} OpenAI tokens (model={})", agentName, total, metadata.getModel());
            }
        } catch (Exception e) {
            log.debug("{}: failed to capture token usage — {}", agentName, e.getMessage());
        }
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
