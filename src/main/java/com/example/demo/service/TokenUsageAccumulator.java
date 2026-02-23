package com.example.demo.service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-request token-usage accumulator backed by an {@link InheritableThreadLocal}.
 *
 * <p>Usage pattern:
 * <pre>
 *   TokenUsageAccumulator.start();          // controller, before pipeline
 *   try {
 *       orchestrator.analyze(file);
 *   } finally {
 *       TokenUsageAccumulator.clear();      // always clean up
 *   }
 *   long openAi     = TokenUsageAccumulator.current().getOpenAiTokens();
 *   long anthropic  = TokenUsageAccumulator.current().getAnthropicTokens();
 * </pre>
 *
 * <p>Virtual threads spawned from the request thread inherit the same accumulator
 * reference, so their token counts are aggregated into the same {@link AtomicLong}
 * objects without any locking overhead.
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

    private final AtomicLong openAiTokens     = new AtomicLong(0);
    private final AtomicLong anthropicTokens  = new AtomicLong(0);

    private TokenUsageAccumulator() {}

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Creates a fresh accumulator for the current thread and returns it.
     * Must be paired with a {@link #clear()} call (preferably in a {@code finally} block).
     */
    public static TokenUsageAccumulator start() {
        TokenUsageAccumulator acc = new TokenUsageAccumulator();
        CONTEXT.set(acc);
        return acc;
    }

    /**
     * Removes the accumulator from the current thread's context.
     * Safe to call even if {@link #start()} was never called.
     */
    public static void clear() {
        CONTEXT.remove();
    }

    /**
     * Returns the accumulator bound to the current thread, or {@code null}
     * if {@link #start()} has not been called.
     */
    public static TokenUsageAccumulator current() {
        return CONTEXT.get();
    }

    // ── Accumulation ─────────────────────────────────────────────────────────

    public void addOpenAiTokens(long tokens) {
        openAiTokens.addAndGet(tokens);
    }

    public void addAnthropicTokens(long tokens) {
        anthropicTokens.addAndGet(tokens);
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    public long getOpenAiTokens() {
        return openAiTokens.get();
    }

    public long getAnthropicTokens() {
        return anthropicTokens.get();
    }
}
