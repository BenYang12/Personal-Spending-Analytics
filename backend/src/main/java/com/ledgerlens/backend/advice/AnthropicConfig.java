package com.ledgerlens.backend.advice;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Builds the Anthropic client bean — same @Bean-for-a-third-party-class pattern
// as my PlaidConfig.
@Configuration
public class AnthropicConfig {

    private static final Logger log = LoggerFactory.getLogger(AnthropicConfig.class);

    // Returning null from a @Bean method means NO bean of this type exists, which
    // is exactly what I want when there's no API key: anything that depends on it
    // must declare that dependency as optional. I could have thrown instead, but
    // then my whole app would refuse to start without an LLM key — for a feature
    // that is explicitly optional, that's the wrong failure.
    @Bean
    public AnthropicClient anthropicClient(
            @Value("${ledgerlens.anthropic.api-key}") String apiKey,
            @Value("${ledgerlens.anthropic.timeout-seconds}") int timeoutSeconds) {

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("No ANTHROPIC_API_KEY set — advice will use the rule-based fallback. "
                    + "This is a supported mode, not an error.");
            return null;
        }

        log.info("Anthropic client configured (timeout {}s)", timeoutSeconds);
        return AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                // A bounded timeout, for the same reason as my scoring client:
                // an unbounded wait on a third-party API ties up request threads
                // until Tomcat's pool is exhausted, turning someone else's outage
                // into mine.
                .timeout(Duration.ofSeconds(timeoutSeconds))
                // The SDK retries 429s and 5xx with backoff on its own. I keep it
                // low because I have my own retry for malformed content on top,
                // and I don't want the two multiplying into a long stall.
                .maxRetries(1)
                .build();
    }
}
