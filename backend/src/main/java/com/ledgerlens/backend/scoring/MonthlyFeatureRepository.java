package com.ledgerlens.backend.scoring;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonthlyFeatureRepository extends JpaRepository<MonthlyFeature, Long> {

    // Mirrors the UNIQUE (account_id, month) constraint: one feature vector per
    // account-month, so recomputing updates in place instead of piling up rows.
    Optional<MonthlyFeature> findByAccountIdAndMonth(Long accountId, LocalDate month);
}
