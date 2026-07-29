package com.ledgerlens.backend.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

// JPA (Jakarta Persistance API) ->  a java specification for mapping Java objects to relational database rows (an ORM)
// It's an interface, not an implementation. Hibernate is the implementation Spring Boot ships by default
// Three layers...
// 1. JPA -> annotations + interfaces -> @Entity, @Id, EntityManager
// 2. Hibernate -> engine that actually does it -> generates the SQL, manages the cache
// 3. Spring Data JPA -> convenience latyer on top -> JpaRepository


// @Entity: Hibernate manages this class; each instance is one row.
// @Table needed because the table is "accounts", not the default "account"
// Account class -> accounts table
// Account instance -> one row in accounts
// field in Account -> column
@Entity
@Table(name = "accounts")
public class Account{
    @Id //primary key
    // IDENTITY = the DATABASE assigns the id (our BIGSERIAL). Hibernate
    // inserts without an id and reads back what Postgres generated.
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String plaidAccountId;
    private String plaidAccessToken;

    private String name;
    private String type;
    private String syncCursor;
    private String syncStatus;

    // the DB fills this via DEFAULT now(). insertable/updatable=false tells 
    // Hibernate: read it, but never write it - the database owns this column
    @Column(insertable=false, updatable = false)
    private Instant createdAt;

    // JPA requires a no-arg constructor (Hibernates instantiates via reflection)
    protected Account(){}

    //currently, nothing can create an account
    // I'll add a public constructor below, and one getter

    public Account(String plaidAccountId, String plaidAccessToken, String name, String type){
        this.plaidAccountId = plaidAccountId;
        this.plaidAccessToken = plaidAccessToken;
        this.name = name;
        this.type = type;
        this.syncStatus = "IDLE";
    }

    // Package-private so only code in this package can read the token
    String getPlaidAccessToken(){
        return plaidAccessToken;
    }



    // Getters only for what other code needs 
    public Long getId(){
        return id;
    }
    public String getName() { return name; }
    public String getType() { return type; }
    public String getSyncStatus() { return syncStatus; }
    public Instant getCreatedAt() { return createdAt; }

    


}
