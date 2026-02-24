package com.example.demo.service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-request token-usage accumulator backed by an {@link InheritableThreadLocal}.
 * Tracks input (prompt) and output (completion) tokens separately for each provider.
 *
 * <p>Virtual threads spawned from the request thread inherit the same accumulator
 * reference, so counts aggregate into the same {@link AtomicLong} objects without
 * any locking overhead.
 */
public final class TokenUsageAccumulator {

    /** Shared across child threads: {@code childValue} returns the parent instance. */
    private static final InheritableThreadLocal<TokenUsageAccumulator> CONTEXT =
            new InheritableThreadLocal<>() {
                @Override
                protected TokenUsageAccumulator childValue(TokenUsageAccumulator parent) {
                    return parent; // intentional: children accumulate into the same counters
                }
            };

    // OpenAI
    private final AtomicLong openAiInputTokens  = new AtomicLong(0);
    private final AtomicLong openAiOutputTokens = new AtomicLong(0);

    // Anthropic
    private final AtomicLong anthropicInputTokens  = new AtomicLong(0);
    private final AtomicLong anthropicOutputTokens = new AtomicLong(0);

    private TokenUsageAccumulator() {}

    // ── Lifecycle ────────────────────────────────────────────────────────────

    public static TokenUsageAccumulator start() {
        TokenUsageAccumulator acc = new TokenUsageAccumulator();
        CONTEXT.set(acc);
        return acc;
    }

    public static void clear() {
        CONTEXT.remove();
    }

    public static TokenUsageAccumulator current() {
        return CONTEXT.get();
    }

    // ── Accumulation ─────────────────────────────────────────────────────────

    public void addOpenAiTokens(long input, long output) {
        openAiInputTokens.addAndGet(input);
        openAiOutputTokens.addAndGet(output);
    }

    public void addAnthropicTokens(long input, long output) {
        anthropicInputTokens.addAndGet(input);
        anthropicOutputTokens.addAndGet(output);
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    public long getOpenAiInputTokens()      { return openAiInputTokens.get(); }
    public long getOpenAiOutputTokens()     { return openAiOutputTokens.get(); }
    public long getOpenAiTotalTokens()      { return openAiInputTokens.get() + openAiOutputTokens.get(); }

    public long getAnthropicInputTokens()   { return anthropicInputTokens.get(); }
    public long getAnthropicOutputTokens()  { return anthropicOutputTokens.get(); }
    public long getAnthropicTotalTokens()   { return anthropicInputTokens.get() + anthropicOutputTokens.get(); }
}
