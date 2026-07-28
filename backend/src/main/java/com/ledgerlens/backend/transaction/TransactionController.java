package com.ledgerlens.backend.transaction;

import java.time.YearMonth;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class TransactionController {

    private final TransactionRepository transactions;

    public TransactionController(TransactionRepository transactions) {
        this.transactions = transactions;
    }

    // GET /api/transactions?accountId=2&month=2026-05
    // @RequestParam pulls query-string values; Spring CONVERTS types for us —
    // "2026-05" becomes a YearMonth via the format hint. Bad input (month=x)
    // is rejected with a 400 before our code runs.
    @GetMapping("/transactions")
    public List<Transaction> forMonth(
            @RequestParam Long accountId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        // YearMonth knows its own calendar: atEndOfMonth() handles 28/30/31.
        // Returning entities (not DTOs) is DELIBERATE here: every Transaction
        // field is safe to expose — contrast with AccountResponse.
        return transactions.findByAccountIdAndPostedDateBetween(
                accountId, month.atDay(1), month.atEndOfMonth());
    }

    // GET /api/summary?accountId=2&month=2026-05
    @GetMapping("/summary")
    public List<CategorySummary> summary(
            @RequestParam Long accountId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return transactions.summarizeByCategory(
                accountId, month.atDay(1), month.atEndOfMonth());
    }
}
