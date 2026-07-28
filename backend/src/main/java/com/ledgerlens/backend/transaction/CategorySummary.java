package com.ledgerlens.backend.transaction;

import java.math.BigDecimal;


// One GROUP BY row: a category, its total, its count. Populated directly by
// the JPQL constructor expression in TransactionRepository — the query calls
// this record's constructor per result row.
public record CategorySummary(String category, BigDecimal total, long count) {
}