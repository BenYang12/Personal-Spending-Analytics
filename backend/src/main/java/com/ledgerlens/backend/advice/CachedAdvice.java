package com.ledgerlens.backend.advice;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// Advice I've already generated, cached per (account, month).
//
// Without this, every dashboard page load is an LLM call — slow, billable, and
// non-deterministic, so the same month's advice would be worded differently each
// time the user refreshed. That last one is the reason I'd cache even if the API
// were free: advice that rewrites itself on every render looks broken.
//
// This is derived data like my model_scores: delete the whole table and it
// regenerates. That's why it can live in its own table with no ceremony.
@Entity
@Table(name = "advice_cache")
public class CachedAdvice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long accountId;

    private LocalDate month;

    // Which path produced this: "claude", "claude-retry", or "rule-based".
    // Cached alongside the text so the dashboard's honesty about provenance
    // survives a cache hit — a cached rule-based answer must not later be
    // presented as model-written.
    private String source;

    @JdbcTypeCode(SqlTypes.JSON)
    private String advice;

    // THE INVALIDATION KEY, and the part of this design I'd actually defend.
    //
    // A plain (account, month) cache is wrong here: the underlying data changes.
    // A Plaid sync adds transactions, a retrain shifts the archetype, a new
    // charge gets flagged — and the cached advice silently describes a month
    // that no longer exists. Time-based expiry doesn't fix it either; it just
    // makes the staleness window shorter and less predictable.
    //
    // So I hash the exact ML inputs the advice was generated from. Same inputs,
    // serve the cache. Different inputs, regenerate. The cache is keyed on what
    // the advice is actually *about*, which means it can never describe stale data.
    private String inputsHash;

    @Column(insertable = false, updatable = false)
    private Instant generatedAt;

    protected CachedAdvice() {}

    public CachedAdvice(Long accountId, LocalDate month, String source,
                        String advice, String inputsHash) {
        this.accountId = accountId;
        this.month = month;
        this.source = source;
        this.advice = advice;
        this.inputsHash = inputsHash;
    }

    public void refresh(String source, String advice, String inputsHash) {
        this.source = source;
        this.advice = advice;
        this.inputsHash = inputsHash;
    }

    public Long getId() { return id; }
    public Long getAccountId() { return accountId; }
    public LocalDate getMonth() { return month; }
    public String getSource() { return source; }
    public String getAdvice() { return advice; }
    public String getInputsHash() { return inputsHash; }
    public Instant getGeneratedAt() { return generatedAt; }
}
