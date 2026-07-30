package com.ledgerlens.backend.scoring;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

// A model's verdict about something, stored SEPARATELY from the thing it judged.
//
// This separation was a deliberate schema decision back in Step 2 and it keeps
// paying off. I could have put an `anomaly_score` column on `transactions`, and
// it would have felt simpler. But then:
//   - retraining would mean UPDATE-ing my source-of-truth financial records,
//     which I never want to do for a derived opinion;
//   - I could only ever hold one score per transaction, so comparing a new
//     model against the old one would mean destroying the old answer;
//   - re-running the pipeline would rewrite rows that are supposed to be an
//     immutable record of what the bank told me.
// Keeping scores in their own table means transactions stay facts and scores
// stay opinions, and I can delete every opinion and rebuild it from scratch.
@Entity
@Table(name = "model_scores")
public class ModelScore {

    // The two kinds of thing I score. Constants rather than scattered string
    // literals, since a typo'd "TRANSACTON" would silently never match a query.
    public static final String SUBJECT_TRANSACTION = "TRANSACTION";
    public static final String SUBJECT_MONTH = "MONTH";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Which kind of subject: a single transaction, or an account-month.
    private String subjectType;

    // Points at transactions.id or monthly_features.id depending on subjectType.
    // A polymorphic reference like this can't be a real foreign key, which is
    // the honest tradeoff: I gave up database-enforced integrity to avoid two
    // near-identical score tables. For a derived, rebuildable table I think
    // that's the right trade — if these rows are ever orphaned I can regenerate
    // the lot.
    private Long subjectId;

    // Versioned on purpose ("kmeans-k6+iforest-c0.02"). When I retrain, new
    // scores land under a new model name and the old ones stay put, so I can
    // compare the two instead of losing the comparison.
    private String modelName;

    private BigDecimal score;

    // The human-readable verdict: an archetype name, or "ANOMALY".
    private String label;

    @Column(insertable = false, updatable = false)
    private Instant scoredAt;

    protected ModelScore() {}

    public ModelScore(String subjectType, Long subjectId, String modelName,
                      BigDecimal score, String label) {
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.modelName = modelName;
        this.score = score;
        this.label = label;
    }

    // Re-scoring the same subject with the same model replaces the verdict
    // rather than adding a second row — the UNIQUE constraint on
    // (subject_type, subject_id, model_name) enforces that at the database
    // level even if my code slips.
    public void update(BigDecimal score, String label) {
        this.score = score;
        this.label = label;
    }

    public Long getId() { return id; }
    public String getSubjectType() { return subjectType; }
    public Long getSubjectId() { return subjectId; }
    public String getModelName() { return modelName; }
    public BigDecimal getScore() { return score; }
    public String getLabel() { return label; }
    public Instant getScoredAt() { return scoredAt; }
}
