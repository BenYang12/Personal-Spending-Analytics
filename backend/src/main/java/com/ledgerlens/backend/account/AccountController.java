package com.ledgerlens.backend.account;

import com.ledgerlens.backend.transaction.TransactionRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// @RestController -> an annotation used to create a class that listens for incoming HTTP web requests and sends raw data (like JSON or XML) straight back to the client.
// = @Component (so component scan registers it as a bean) + "return values are the response body, serialized to JSON by Jackson"
@RestController
@RequestMapping("/api") // URL prefix for whole class. Every route inside gets /api prepended
public class AccountController{
    // CONSTRUCTOR INJECTION
    private final AccountRepository accounts;
    private final TransactionRepository transactions;

    public AccountController(AccountRepository accounts, TransactionRepository transactions){
        this.accounts = accounts;
        this.transactions = transactions;
    }

    @GetMapping("/accounts") // full path: GET /api/accounts
    public List<AccountResponse> all(){
        // TWO queries total, regardless of how many accounts exist: one for the
        // accounts, one for all per-account-month activity. I then join them in
        // memory. The obvious alternative — looping accounts and counting each —
        // is the N+1 problem, and it scales badly for no reason when a single
        // GROUP BY already has the answer.
        Map<Long, List<String>> monthsByAccount = new HashMap<>();
        Map<Long, Long> countsByAccount = new HashMap<>();

        for (var row : transactions.findActivityByAccountMonth()) {
            monthsByAccount
                    .computeIfAbsent(row.getAccountId(), id -> new ArrayList<>())
                    .add(row.getMonth());
            countsByAccount.merge(row.getAccountId(), row.getTransactionCount(), Long::sum);
        }

        // Entities in, DTOs out — the token stays behind, by construction.
        return accounts.findAll().stream()
                .map(account -> AccountResponse.from(
                        account,
                        countsByAccount.getOrDefault(account.getId(), 0L),
                        // Newest month first: the dashboard defaults to the most
                        // recent month with data, which is what someone opening
                        // their finances wants to see.
                        monthsByAccount.getOrDefault(account.getId(), List.of())
                                .stream()
                                .sorted((a, b) -> b.compareTo(a))
                                .toList()))
                .toList();
    }
}
