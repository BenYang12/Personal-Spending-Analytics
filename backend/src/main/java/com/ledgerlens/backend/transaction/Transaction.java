package com.ledgerlens.backend.transaction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "transactions")
public class Transaction{
    //columns
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String plaidTransactionId;
    //My Design choice -> foreign key as plain Long, NOT a @ManyToOne Account relationship (many transactions belong to one account)
    // We only ever filter by account id;
    // we never need to walk transaction.getAccount().getName(). Skipping the
    // object graph avoids JPA's classic lazy-loading traps entirely.
    // (Interview answer: "I mapped the FK as a value; I didn't need a graph.")
    private Long accountId;
    private LocalDate postedDate;
    private BigDecimal amount;
    private String merchant;
    private String category;
    private boolean pending;
    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    // constructor
    protected Transaction(){}

    public Transaction(String plaidTransactionId, Long accountId, LocalDate postedDate, BigDecimal amount, String merchant, String category, boolean pending) {
        this.plaidTransactionId = plaidTransactionId;
        this.accountId = accountId;
        this.postedDate = postedDate;
        this.amount = amount;
        this.merchant = merchant;
        this.category = category;
        this.pending = pending;
    }

    // For Plaid's "modified" list: a pending charge settling with a final
    // amount and a real merchant name. 
    // Because this object is loaded inside a @Transactional method, Hibernate
    // sees these field changes and flushes an UPDATE at commit. I never
    // call save() for an already-loaded entity — that's "dirty checking".
    public void updateFrom(LocalDate postedDate, BigDecimal amount, String merchant, String category, boolean pending) {
        this.postedDate = postedDate;
        this.amount = amount;
        this.merchant = merchant;
        this.category = category;
        this.pending = pending;
    }




    // getters
    public Long getId() { return id; }
    public String getPlaidTransactionId() { return plaidTransactionId; }
    public Long getAccountId() { return accountId; }
    public LocalDate getPostedDate() { return postedDate; }
    public BigDecimal getAmount() { return amount; }
    public String getMerchant() { return merchant; }
    public String getCategory() { return category; }
    public boolean isPending() { return pending; }
}
