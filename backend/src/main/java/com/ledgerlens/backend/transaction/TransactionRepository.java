package com.ledgerlens.backend.transaction;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    // "Derived queries": Spring Data PARSES THE METHOD NAME into SQL at startup
    // FindBy + AccountId + And + PostedDate + Between becomes:
    //  WHERE account_id = ? AND posted_date BETWEEN ? AND ?
    List<Transaction> findByAccountIdAndPostedDateBetween(
        Long accountId, LocalDate start, LocalDate end
    );

    List<Transaction> findByCategory(String category);

   
}
