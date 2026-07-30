package com.ledgerlens.backend.plaid;

import com.ledgerlens.backend.account.Account;
import com.ledgerlens.backend.account.AccountRepository;
import com.ledgerlens.backend.transaction.Transaction;
import com.ledgerlens.backend.transaction.TransactionRepository;
import com.plaid.client.model.RemovedTransaction;
import com.plaid.client.model.TransactionsSyncRequest;
import com.plaid.client.model.TransactionsSyncResponse;
import com.plaid.client.request.PlaidApi;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import retrofit2.Response;

// @Service: same bean registration as @Component, but names the role - business logic
// Controllers stay thin: parse the request, call a service. 
@Service
public class PlaidSyncService{
    private static final Logger log = LoggerFactory.getLogger(PlaidSyncService.class);
    private final PlaidApi plaid;
    private final AccountRepository accounts;
    private final TransactionRepository transactions;

    public PlaidSyncService(PlaidApi plaid, AccountRepository accounts, TransactionRepository transactions) {
        this.plaid = plaid;
        this.accounts = accounts;
        this.transactions = transactions;
    }

    // A record to report what a sync did 
    public record SyncResult(int added, int modified, int removed, int pages) {}


    // Entry point: sync every linked Item once.
    // The grouping: the cursor is per-ITEM (per access_token). HOWEVER, my schema stores it per account.
    // grouping by token means one API conversation per bank connection
    // @Transactional MUST sit here, on the method the CONTROLLER calls — not on
    // syncItem below. Spring implements @Transactional with a PROXY: the bean
    // injected into the controller is a wrapper that opens a transaction, calls
    // the real method, then commits. A call from syncAll() to this.syncItem()
    // goes straight to the real object and NEVER PASSES THROUGH THE PROXY, so
    // the annotation there would be silently ignored — no transaction, and
    // Hibernate's dirty-checking updates in upsert() would never be flushed.
    // "Self-invocation defeats @Transactional" is a top-5 Spring interview
    // question precisely because it fails quietly.
    // Tradeoff of putting it here: all Items commit or roll back together
    // rather than one at a time. Fine for us (one Item); a multi-bank app
    // would extract per-Item sync into its own bean to get separate proxies.
    @Transactional
    public SyncResult syncAll() throws IOException {
        // Account::getPlaidAccessToken tells the code to look at token text inside each account object and use that token as the map key 
        Map<String, List<Account>> byItem = accounts.findByPlaidAccessTokenIsNotNull()
                .stream()
                .collect(Collectors.groupingBy(Account::getPlaidAccessToken));

        int added = 0, modified = 0, removed = 0, pages = 0;
        for (List<Account> itemAccounts : byItem.values()) {
            SyncResult r = syncItem(itemAccounts);
            added += r.added(); modified += r.modified();
            removed += r.removed(); pages += r.pages();
        }
        log.info("sync complete: +{} ~{} -{} across {} pages", added, modified, removed, pages);
        return new SyncResult(added, modified, removed, pages);
    }
    // Runs inside the transaction opened by syncAll() (see the note there on why
    // the annotation can't live here). A crash mid-loop rolls everything back,
    // INCLUDING the cursor — so a failed sync is safely retryable.
    private SyncResult syncItem(List<Account> itemAccounts) throws IOException {
        String accessToken = itemAccounts.getFirst().getPlaidAccessToken();

        // Plaid identifies accounts by ITS id; our transactions table uses OUR
        // BIGSERIAL id. Build the translation table once, up front.
        Map<String, Long> plaidToLocalId = itemAccounts.stream()
                .collect(Collectors.toMap(Account::getPlaidAccountId, Account::getId));

        // Resume point. null on the very first sync = "give me everything".
        String cursor = itemAccounts.getFirst().getSyncCursor();
        int added = 0, modified = 0, pages = 0;
        List<String> removedIds = new ArrayList<>();

        boolean hasMore = true;
        while (hasMore) {
            TransactionsSyncRequest request = new TransactionsSyncRequest().accessToken(accessToken);
            // Send the cursor only if we have one — passing null is an error.
            if (cursor != null && !cursor.isBlank()) {
                request.cursor(cursor);
            }

            Response<TransactionsSyncResponse> response = plaid.transactionsSync(request).execute();
            if (!response.isSuccessful()) {
                // Throwing inside @Transactional rolls everything back, INCLUDING
                // the cursor — so a failed sync is safely retryable.
                throw new IllegalStateException("Plaid sync failed: " + response.errorBody().string());
            }
            TransactionsSyncResponse body = response.body();
            pages++;

            for (com.plaid.client.model.Transaction t : body.getAdded()) {
                if (upsert(t, plaidToLocalId)) added++;
            }
            for (com.plaid.client.model.Transaction t : body.getModified()) {
                upsert(t, plaidToLocalId);
                modified++;
            }
            for (RemovedTransaction r : body.getRemoved()) {
                removedIds.add(r.getTransactionId());
            }

            // Advance the bookmark and decide whether to loop again. Each page
            // hands us the cursor for the next one.
            cursor = body.getNextCursor();
            hasMore = Boolean.TRUE.equals(body.getHasMore());
        }

        int removed = removedIds.isEmpty() ? 0
                : (int) transactions.deleteByPlaidTransactionIdIn(removedIds);

        // Persist the cursor ONLY after all pages succeeded — that ordering is
        // what makes the whole operation safe to retry.
        for (Account account : itemAccounts) {
            account.setSyncCursor(cursor);
            account.setSyncStatus("IDLE");
        }
        return new SyncResult(added, modified, removed, pages);
    }
    // THE IDEMPOTENCY CORE. Look up by Plaid's stable id: found -> update,
    // absent -> insert. Run this twice with identical input and the database
    // is identical, which is exactly what "idempotent" means.
    // Belt and braces: the UNIQUE constraint on plaid_transaction_id (Step 2)
    // would reject a duplicate even if this logic were wrong. Correctness in
    // the app AND in the schema.
    // Returns true only when a row was actually inserted.
    private boolean upsert(com.plaid.client.model.Transaction t, Map<String, Long> plaidToLocalId) {
        Long accountId = plaidToLocalId.get(t.getAccountId());
        if (accountId == null) {
            // A transaction for an account we never stored. Skip loudly rather
            // than crash the whole sync over one orphan row.
            log.warn("skipping txn for unknown plaid account {}", t.getAccountId());
            return false;
        }

        // Plaid gives amount as a Double; convert via valueOf, which goes
        // through the decimal string form. NEVER `new BigDecimal(double)` —
        // that captures binary float noise (0.1 becomes 0.1000000000000000055).
        BigDecimal amount = BigDecimal.valueOf(t.getAmount());
        String merchant = merchantName(t);
        String category = categoryName(t);
        boolean pending = Boolean.TRUE.equals(t.getPending());

        Optional<Transaction> existing = transactions.findByPlaidTransactionId(t.getTransactionId());
        if (existing.isPresent()) {
            // Dirty checking: mutate the loaded entity, Hibernate writes the
            // UPDATE at commit. No save() call needed.
            existing.get().updateFrom(t.getDate(), amount, merchant, category, pending);
            return false;
        }

        transactions.save(new Transaction(t.getTransactionId(), accountId,
                t.getDate(), amount, merchant, category, pending));
        return true;
    }
        // Real-world data is full of nulls. merchant_name is the cleaned-up
    // merchant ("Starbucks"); name is the raw bank descriptor
    // ("STARBUCKS #2140 PURCHASE"). Prefer clean, fall back to raw, then to a
    // placeholder — our column is NOT NULL, so this method may never return null.
    private String merchantName(com.plaid.client.model.Transaction t) {
        if (t.getMerchantName() != null && !t.getMerchantName().isBlank()) {
            return t.getMerchantName();
        }
        return (t.getName() != null && !t.getName().isBlank()) ? t.getName() : "Unknown";
    }

    // Plaid's personal_finance_category gives a primary label like
    // "FOOD_AND_DRINK". Phase 3 clusters on these, so a stable fallback
    // matters more than a pretty one — an inconsistent "misc" would smear
    // across clusters.
    private String categoryName(com.plaid.client.model.Transaction t) {
        if (t.getPersonalFinanceCategory() != null
                && t.getPersonalFinanceCategory().getPrimary() != null) {
            return t.getPersonalFinanceCategory().getPrimary();
        }
        return "OTHER";
    }
}



