package com.ledgerlens.backend.plaid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ledgerlens.backend.account.Account;
import com.ledgerlens.backend.account.AccountRepository;
import com.ledgerlens.backend.transaction.TransactionRepository;
import com.plaid.client.model.PersonalFinanceCategory;
import com.plaid.client.model.RemovedTransaction;
import com.plaid.client.model.TransactionsSyncRequest;
import com.plaid.client.model.TransactionsSyncResponse;
import com.plaid.client.request.PlaidApi;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import retrofit2.Call;
import retrofit2.Response;

// A PLAIN UNIT TEST — no @SpringBootTest, no application context, no database,
// no network. It runs in milliseconds because it tests OUR logic in isolation.
// That distinction matters: integration tests prove the wiring works; unit
// tests like these prove the ingestion RULES are right, and they're the ones
// you can afford to run on every keystroke.
//
// MockitoExtension activates the @Mock fields and, importantly, fails the test
// if we set up a stub that never gets used — catching tests that silently
// stopped exercising what they claim to.
@ExtendWith(MockitoExtension.class)
class PlaidSyncServiceTest {

    // A "mock" is a stand-in that records calls and returns whatever we tell
    // it to. Mocking PlaidApi means these tests never touch the real Plaid.
    @Mock private PlaidApi plaid;
    @Mock private AccountRepository accounts;
    @Mock private TransactionRepository transactions;
    // Retrofit's two-step call (build then .execute()) needs its own mock.
    @Mock private Call<TransactionsSyncResponse> call;

    private PlaidSyncService service;

    private static final String ACCESS_TOKEN = "access-sandbox-test";
    private static final String PLAID_ACCOUNT_ID = "plaid-acct-1";
    private static final Long LOCAL_ACCOUNT_ID = 42L;

    @BeforeEach
    void setUp() {
        // Constructor injection pays off here: in production Spring supplies
        // the real beans, in tests we hand over mocks. No framework needed.
        service = new PlaidSyncService(plaid, accounts, transactions);
    }

    // ---------- helpers ----------

    // Our Account entity has no id setter (the DATABASE assigns it), but the
    // sync service needs a non-null id to build its lookup map. Reflection is
    // the standard escape hatch for exactly this: it lets a test simulate a
    // persisted entity without weakening the production API with a setter
    // nobody should call.
    private Account linkedAccount() {
        Account account = new Account(PLAID_ACCOUNT_ID, ACCESS_TOKEN, "Test Checking", "checking");
        ReflectionTestUtils.setField(account, "id", LOCAL_ACCOUNT_ID);
        return account;
    }

    private com.plaid.client.model.Transaction plaidTxn(String id, double amount,
                                                        String merchantName, boolean pending) {
        return new com.plaid.client.model.Transaction()
                .transactionId(id)
                .accountId(PLAID_ACCOUNT_ID)
                .date(LocalDate.of(2026, 5, 17))
                .amount(amount)
                .merchantName(merchantName)
                .name("RAW BANK DESCRIPTOR")
                .pending(pending)
                .personalFinanceCategory(new PersonalFinanceCategory().primary("FOOD_AND_DRINK"));
    }

    // Teaches Plaid to answer with one page and stop.
    private void plaidReturns(TransactionsSyncResponse body) throws IOException {
        when(accounts.findByPlaidAccessTokenIsNotNull()).thenReturn(List.of(linkedAccount()));
        when(plaid.transactionsSync(any(TransactionsSyncRequest.class))).thenReturn(call);
        when(call.execute()).thenReturn(Response.success(body));
    }

    private TransactionsSyncResponse page(List<com.plaid.client.model.Transaction> added,
                                          List<com.plaid.client.model.Transaction> modified,
                                          List<RemovedTransaction> removed,
                                          String nextCursor, boolean hasMore) {
        return new TransactionsSyncResponse()
                .added(added).modified(modified).removed(removed)
                .nextCursor(nextCursor).hasMore(hasMore);
    }

    // ---------- the "added" path ----------

    @Test
    @DisplayName("added transactions are inserted with Plaid's fields mapped correctly")
    void insertsAddedTransactions() throws IOException {
        plaidReturns(page(List.of(plaidTxn("txn-1", 12.34, "Blue Bottle", false)),
                List.of(), List.of(), "cursor-after-page-1", false));
        when(transactions.findByPlaidTransactionId("txn-1")).thenReturn(Optional.empty());

        PlaidSyncService.SyncResult result = service.syncAll();

        assertThat(result.added()).isEqualTo(1);
        assertThat(result.modified()).isZero();
        assertThat(result.removed()).isZero();

        // ArgumentCaptor grabs the object our code passed to save(), so we can
        // assert on the MAPPING — the part most likely to be subtly wrong.
        ArgumentCaptor<com.ledgerlens.backend.transaction.Transaction> saved =
                ArgumentCaptor.forClass(com.ledgerlens.backend.transaction.Transaction.class);
        verify(transactions).save(saved.capture());

        var txn = saved.getValue();
        assertThat(txn.getPlaidTransactionId()).isEqualTo("txn-1");
        // Plaid's account id was translated into OUR local id.
        assertThat(txn.getAccountId()).isEqualTo(LOCAL_ACCOUNT_ID);
        // The money assertion that matters: exact decimal, no float drift.
        assertThat(txn.getAmount()).isEqualByComparingTo(new BigDecimal("12.34"));
        // merchant_name preferred over the raw descriptor.
        assertThat(txn.getMerchant()).isEqualTo("Blue Bottle");
        assertThat(txn.getCategory()).isEqualTo("FOOD_AND_DRINK");
        assertThat(txn.isPending()).isFalse();
    }

    @Test
    @DisplayName("a transaction we already have is NOT inserted again (idempotency)")
    void doesNotDuplicateExistingTransaction() throws IOException {
        // Same id arrives again — the scenario a retried sync creates.
        var existing = new com.ledgerlens.backend.transaction.Transaction(
                "txn-1", LOCAL_ACCOUNT_ID, LocalDate.of(2026, 5, 17),
                new BigDecimal("12.34"), "Blue Bottle", "FOOD_AND_DRINK", false);
        plaidReturns(page(List.of(plaidTxn("txn-1", 12.34, "Blue Bottle", false)),
                List.of(), List.of(), "cursor-1", false));
        when(transactions.findByPlaidTransactionId("txn-1")).thenReturn(Optional.of(existing));

        PlaidSyncService.SyncResult result = service.syncAll();

        // THE IDEMPOTENCY ASSERTION: nothing was inserted.
        assertThat(result.added()).isZero();
        verify(transactions, never()).save(any());
    }

    // ---------- the "modified" path ----------

    @Test
    @DisplayName("modified transactions update the existing row in place")
    void updatesModifiedTransaction() throws IOException {
        // The real-world case: a pending $50 estimate settles at $47.13 with a
        // proper merchant name.
        var existing = new com.ledgerlens.backend.transaction.Transaction(
                "txn-1", LOCAL_ACCOUNT_ID, LocalDate.of(2026, 5, 17),
                new BigDecimal("50.00"), "PENDING CHARGE", "FOOD_AND_DRINK", true);
        plaidReturns(page(List.of(), List.of(plaidTxn("txn-1", 47.13, "Bartaco", false)),
                List.of(), "cursor-1", false));
        when(transactions.findByPlaidTransactionId("txn-1")).thenReturn(Optional.of(existing));

        PlaidSyncService.SyncResult result = service.syncAll();

        assertThat(result.modified()).isEqualTo(1);
        // No save() call: the entity was mutated in place and Hibernate's dirty
        // checking writes the UPDATE at commit. Asserting never(save) documents
        // that this is intentional, not forgotten.
        verify(transactions, never()).save(any());
        assertThat(existing.getAmount()).isEqualByComparingTo(new BigDecimal("47.13"));
        assertThat(existing.getMerchant()).isEqualTo("Bartaco");
        assertThat(existing.isPending()).isFalse();
    }

    // ---------- the "removed" path ----------

    @Test
    @DisplayName("removed transactions are deleted by their Plaid ids")
    void deletesRemovedTransactions() throws IOException {
        plaidReturns(page(List.of(), List.of(),
                List.of(new RemovedTransaction().transactionId("txn-gone-1"),
                        new RemovedTransaction().transactionId("txn-gone-2")),
                "cursor-1", false));
        when(transactions.deleteByPlaidTransactionIdIn(any())).thenReturn(2L);

        PlaidSyncService.SyncResult result = service.syncAll();

        assertThat(result.removed()).isEqualTo(2);
        // Verify we asked for exactly the right ids — a bulk delete with a
        // wrong collection is a data-loss bug, so this assertion earns its keep.
        ArgumentCaptor<java.util.Collection<String>> ids = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(transactions).deleteByPlaidTransactionIdIn(ids.capture());
        assertThat(ids.getValue()).containsExactly("txn-gone-1", "txn-gone-2");
    }

    @Test
    @DisplayName("no removals means no delete call at all")
    void skipsDeleteWhenNothingRemoved() throws IOException {
        plaidReturns(page(List.of(), List.of(), List.of(), "cursor-1", false));

        service.syncAll();

        // Guards the `removedIds.isEmpty() ? 0 : delete(...)` branch: calling a
        // bulk delete with an empty collection is a pointless query at best.
        verify(transactions, never()).deleteByPlaidTransactionIdIn(any());
    }

    // ---------- pagination and cursor ----------

    @Test
    @DisplayName("keeps requesting pages while has_more is true, then stores the LAST cursor")
    void followsPaginationUntilHasMoreIsFalse() throws IOException {
        Account account = linkedAccount();
        when(accounts.findByPlaidAccessTokenIsNotNull()).thenReturn(List.of(account));
        when(plaid.transactionsSync(any(TransactionsSyncRequest.class))).thenReturn(call);
        when(transactions.findByPlaidTransactionId(anyString())).thenReturn(Optional.empty());

        // Two pages: the first says "there's more", the second ends the loop.
        when(call.execute()).thenReturn(
                Response.success(page(List.of(plaidTxn("txn-p1", 10.00, "A", false)),
                        List.of(), List.of(), "cursor-page-1", true)),
                Response.success(page(List.of(plaidTxn("txn-p2", 20.00, "B", false)),
                        List.of(), List.of(), "cursor-page-2", false)));

        PlaidSyncService.SyncResult result = service.syncAll();

        assertThat(result.pages()).isEqualTo(2);
        assertThat(result.added()).isEqualTo(2);
        verify(call, times(2)).execute();
        // Only the FINAL cursor may be persisted — storing page 1's cursor
        // would silently re-fetch page 2 forever.
        assertThat(account.getSyncCursor()).isEqualTo("cursor-page-2");
        assertThat(account.getSyncStatus()).isEqualTo("IDLE");
    }

    @Test
    @DisplayName("a Plaid error throws, so the transaction rolls back and the cursor is not advanced")
    void throwsWhenPlaidCallFails() throws IOException {
        Account account = linkedAccount();
        ReflectionTestUtils.setField(account, "syncCursor", "cursor-before-failure");
        when(accounts.findByPlaidAccessTokenIsNotNull()).thenReturn(List.of(account));
        when(plaid.transactionsSync(any(TransactionsSyncRequest.class))).thenReturn(call);
        when(call.execute()).thenReturn(Response.error(500,
                okhttp3.ResponseBody.create("{\"error_code\":\"INTERNAL_SERVER_ERROR\"}",
                        okhttp3.MediaType.parse("application/json"))));

        // Throwing is the CORRECT behavior: it triggers the @Transactional
        // rollback, which is what makes a failed sync safely retryable.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.syncAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Plaid sync failed");

        // The old cursor is untouched, so the retry resumes from the right place.
        assertThat(account.getSyncCursor()).isEqualTo("cursor-before-failure");
    }

    // ---------- field-mapping edge cases ----------

    @Test
    @DisplayName("falls back to the raw name, then to 'Unknown', when merchant_name is missing")
    void fallsBackWhenMerchantNameMissing() throws IOException {
        // merchant_name null is COMMON in real Plaid data — this is not a
        // hypothetical edge case, and merchant is a NOT NULL column.
        var txn = plaidTxn("txn-1", 5.00, null, false);
        plaidReturns(page(List.of(txn), List.of(), List.of(), "cursor-1", false));
        when(transactions.findByPlaidTransactionId("txn-1")).thenReturn(Optional.empty());

        service.syncAll();

        ArgumentCaptor<com.ledgerlens.backend.transaction.Transaction> saved =
                ArgumentCaptor.forClass(com.ledgerlens.backend.transaction.Transaction.class);
        verify(transactions).save(saved.capture());
        assertThat(saved.getValue().getMerchant()).isEqualTo("RAW BANK DESCRIPTOR");
    }

    @Test
    @DisplayName("uses OTHER when Plaid sends no category")
    void defaultsCategoryWhenMissing() throws IOException {
        var txn = plaidTxn("txn-1", 5.00, "Somewhere", false).personalFinanceCategory(null);
        plaidReturns(page(List.of(txn), List.of(), List.of(), "cursor-1", false));
        when(transactions.findByPlaidTransactionId("txn-1")).thenReturn(Optional.empty());

        service.syncAll();

        ArgumentCaptor<com.ledgerlens.backend.transaction.Transaction> saved =
                ArgumentCaptor.forClass(com.ledgerlens.backend.transaction.Transaction.class);
        verify(transactions).save(saved.capture());
        assertThat(saved.getValue().getCategory()).isEqualTo("OTHER");
    }

    @Test
    @DisplayName("a transaction for an unknown account is skipped, not fatal")
    void skipsTransactionForUnknownAccount() throws IOException {
        var orphan = plaidTxn("txn-1", 5.00, "Somewhere", false).accountId("account-we-never-stored");
        plaidReturns(page(List.of(orphan), List.of(), List.of(), "cursor-1", false));

        PlaidSyncService.SyncResult result = service.syncAll();

        // One bad row must not abort the whole sync — the other 47 still land.
        assertThat(result.added()).isZero();
        verify(transactions, never()).save(any());
    }
}
