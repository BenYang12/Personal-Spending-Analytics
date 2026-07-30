package com.ledgerlens.backend.scoring;

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

// The feature vector a month was scored from — derived data, rebuildable.
//
// I store this so my scores are AUDITABLE. A stored anomaly score with no
// record of its inputs is unexplainable after the fact: months later I'd have
// the verdict but no way to reconstruct why. In a financial app "why was I
// flagged?" is a question I must be able to answer, so the score and the
// numbers behind it get written together.
@Entity
@Table(name = "monthly_features")
public class MonthlyFeature {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long accountId;

    // Always the first of the month — the convention my whole pipeline uses.
    private LocalDate month;

    // JSONB. @JdbcTypeCode(SqlTypes.JSON) is how I tell Hibernate this String
    // is really a JSON column, so it binds correctly instead of trying to cram
    // text into jsonb and failing.
    //
    // I chose JSONB over one column per feature deliberately, and I'd defend it
    // this way: my feature set is still moving (it changed three times during
    // Phase 4 alone), and each change would otherwise be a schema migration. The
    // cost is no type checking inside the blob — acceptable for derived data I
    // can regenerate, and NOT something I'd accept for the transactions table.
    @JdbcTypeCode(SqlTypes.JSON)
    private String features;

    @Column(insertable = false, updatable = false)
    private Instant computedAt;

    protected MonthlyFeature() {}

    public MonthlyFeature(Long accountId, LocalDate month, String features) {
        this.accountId = accountId;
        this.month = month;
        this.features = features;
    }

    // Recomputing replaces the vector rather than adding a row — enforced by
    // the UNIQUE (account_id, month) constraint in my schema.
    public void updateFeatures(String features) {
        this.features = features;
    }

    public Long getId() { return id; }
    public Long getAccountId() { return accountId; }
    public LocalDate getMonth() { return month; }
    public String getFeatures() { return features; }
    public Instant getComputedAt() { return computedAt; }
}
