package com.ledgerlens.backend.advice;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CachedAdviceRepository extends JpaRepository<CachedAdvice, Long> {

    // Mirrors the UNIQUE (account_id, month) constraint: one cached answer per
    // account-month, refreshed in place when the inputs hash changes.
    Optional<CachedAdvice> findByAccountIdAndMonth(Long accountId, LocalDate month);
}
