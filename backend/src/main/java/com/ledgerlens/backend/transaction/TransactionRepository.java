package com.ledgerlens.backend.transaction;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    // "Derived queries": Spring Data PARSES THE METHOD NAME into SQL at startup
    // FindBy + AccountId + And + PostedDate + Between becomes:
    //  WHERE account_id = ? AND posted_date BETWEEN ? AND ?
    List<Transaction> findByAccountIdAndPostedDateBetween(
        Long accountId, LocalDate start, LocalDate end
    );

    List<Transaction> findByCategory(String category);

   
    // Method names can't express GROUP BY ->  eed explicit JPQL. Note it
    // queries the ENTITY (Transaction, t.postedDate), not the table: Hibernate
    // translates to SQL. "new ..." is a constructor expression: each result
    // row is poured straight into a CategorySummary (full package name
    // required by JPQL). :accountId binds the method parameter by name.
    @Query("""
            SELECT new com.ledgerlens.backend.transaction.CategorySummary(
                t.category, SUM(t.amount), COUNT(t))
            FROM Transaction t
            WHERE t.accountId = :accountId AND t.postedDate BETWEEN :start AND :end
            GROUP BY t.category
            ORDER BY SUM(t.amount) DESC""")
    List<CategorySummary> summarizeByCategory(Long accountId, LocalDate start, LocalDate end);
}
