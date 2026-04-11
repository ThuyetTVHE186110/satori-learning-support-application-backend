package com.fu.curriculumimport.util;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.stream.Collectors;

/**
 * Collects a streaming LLM response ({@link Flux}&lt;String&gt;) into a single
 * {@code String} — a drop-in replacement for the blocking
 * {@code .call().content()} pattern.
 *
 * <h3>Why streaming?</h3>
 * With {@code .call().content()}, the HTTP connection stays <em>idle</em> while
 * the model generates tokens internally.  Long extractions (16 K output tokens
 * on gpt-4o) can idle for 2–5 minutes, triggering
 * {@code SocketTimeoutException: Read timed out} regardless of timeout config.
 * <p>
 * Streaming solves this at the protocol level: tokens arrive every ~50 ms, so
 * the socket is <b>never idle</b> and no read-timeout can fire.  The
 * wall-clock timeout passed to {@link #collect} guards against genuinely stuck
 * requests (network partition, API outage), not normal generation latency.
 *
 * @see <a href="https://github.com/spring-projects/spring-ai/discussions/2195">
 *      Spring AI Discussion #2195 — ReadTimeoutException</a>
 * @see <a href="https://github.com/spring-projects/spring-ai/issues/354">
 *      Spring AI Issue #354 — Client timeout configuration</a>
 */
@Slf4j
public final class LlmStreamCollector {

    /** Default wall-clock timeout for a single LLM generation. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    private LlmStreamCollector() { /* utility */ }

    /**
     * Collect streaming tokens into a single String using the default 5-minute
     * wall-clock timeout.
     */
    public static String collect(Flux<String> flux) {
        return collect(flux, DEFAULT_TIMEOUT);
    }

    /**
     * Collect streaming tokens into a single String.
     *
     * @param flux    the streaming content from
     *                {@code chatClient.prompt()...stream().content()}
     * @param timeout maximum wall-clock time to wait for the full response;
     *                this is NOT a socket idle timeout — tokens keep flowing,
     *                so only genuinely stuck calls will hit this
     * @return the concatenated response text
     * @throws IllegalStateException if the flux completes empty
     *         (upstream returned no tokens)
     */
    public static String collect(Flux<String> flux, Duration timeout) {
        return flux
                .collectList()
                .block(timeout)
                .stream()
                .collect(Collectors.joining());
    }
}
