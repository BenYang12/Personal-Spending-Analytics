package com.ledgerlens.backend.plaid;

import com.ledgerlens.backend.account.SyncStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

// A SEPARATE BEAN from PlaidSyncService, and that is the whole point.
// @Async works by proxy exactly like @Transactional: only calls that arrive
// from OUTSIDE the object get intercepted. If this method lived on
// PlaidSyncService and called this.syncAll(), the call would bypass the
// @Transactional proxy — the same self-invocation trap as Step 8. Two beans,
// two proxies: the async boundary and the transaction boundary both hold.
@Component
public class PlaidSyncJob {

    private static final Logger log = LoggerFactory.getLogger(PlaidSyncJob.class);

    private final PlaidSyncService syncService;
    private final SyncStatusService syncStatus;

    public PlaidSyncJob(PlaidSyncService syncService, SyncStatusService syncStatus) {
        this.syncService = syncService;
        this.syncStatus = syncStatus;
    }

    // @Async: returns to the caller IMMEDIATELY and runs the body on a thread
    // from the named pool. The controller can respond 202 in milliseconds while
    // Plaid takes its time.
    // void return type is deliberate: this is fire-and-forget, and progress is
    // reported through accounts.sync_status rather than a Future nobody holds.
    @Async("plaidSyncExecutor")
    public void runSync() {
        try {
            // Goes through PlaidSyncService's proxy (different bean), so
            // @Transactional there genuinely applies — on this thread too.
            PlaidSyncService.SyncResult result = syncService.syncAll();
            log.info("async sync finished: +{} ~{} -{} over {} pages",
                    result.added(), result.modified(), result.removed(), result.pages());
            // syncAll() already set each account to IDLE inside its transaction.
        } catch (Exception e) {
            // CRITICAL for async work: nobody is waiting on this call, so an
            // uncaught exception would vanish into the executor's thread with
            // no HTTP response to carry it. We must catch, log, and record the
            // failure ourselves — otherwise accounts sit at "SYNCING" forever
            // and the UI lies about what happened.
            log.error("async sync failed", e);
            syncStatus.mark(SyncStatusService.FAILED);
        }
    }
}
