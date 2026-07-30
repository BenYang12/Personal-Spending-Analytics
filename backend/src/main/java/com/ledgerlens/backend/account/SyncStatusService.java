package com.ledgerlens.backend.account;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Why this is its own bean rather than two methods on PlaidSyncService:
// each status change must land in the database on ITS OWN, immediately.
// If marking "SYNCING" shared a transaction with the sync itself, nobody
// could see it until that transaction committed — by which point the sync
// is already finished and the status is meaningless. Separate bean =
// separate proxy = separate transaction, guaranteed.
@Service
public class SyncStatusService {

    // These are the only legal values of accounts.sync_status. Constants beat
    // scattered string literals; a typo'd "SYNCNG" would fail silently.
    // (An enum + @Enumerated would be stricter still — a reasonable upgrade.)
    public static final String IDLE = "IDLE";
    public static final String SYNCING = "SYNCING";
    public static final String FAILED = "FAILED";

    private final AccountRepository accounts;

    public SyncStatusService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    // @Transactional here commits the moment this method returns, so the
    // status is visible to other requests before the sync even begins.
    @Transactional
    public void mark(String status) {
        accounts.updateSyncStatusForLinkedAccounts(status);
    }
}
