package com.ledgerlens.backend.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

// An interface with NO implementation anywhere
// Why?...
// At startup, Spring Data sees it, generates a working implementation (using Hibernate underneath), and registers it as a bean
// -> injectable wherever we declare it as a dependency.
// JpaRepository<Account, Long> = "rows of Account, primary key type Long";
// that alone gives us findAll(), findById(), save(), count(), delete()...
// JpaRepository<Account, Long> -> <entity, its primary key>, holds just account objects, nothing in it is a pair
public interface AccountRepository extends JpaRepository<Account,Long>{
    // Optional<> b/c it may not exist
    java.util.Optional<Account> findByPlaidAccountId(String plaidAccountId);
    java.util.List<Account> findByPlaidAccessTokenIsNotNull(); // "IsNotNull" is another derived-query keyword. Skips two hand-seeded accounts that have no token

    // A BULK UPDATE: one SQL statement for all linked accounts, instead of
    // loading 12 entities and mutating each. @Modifying is required for any
    // @Query that writes (Spring Data assumes SELECT otherwise).
    // clearAutomatically = the persistence context is emptied afterward, since
    // its cached copies are now stale relative to the database.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Account a SET a.syncStatus = :status WHERE a.plaidAccessToken IS NOT NULL")
    int updateSyncStatusForLinkedAccounts(String status);
}