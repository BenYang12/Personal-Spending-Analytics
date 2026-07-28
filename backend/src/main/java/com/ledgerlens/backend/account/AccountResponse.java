// Notes
// A @RestController is a bean whose methods handle HTTP requests: @GetMapping("/api/accounts") maps a url to a method
// DTO (data transfer object) -> Java records are ideal DTOs.
package com.ledgerlens.backend.account;

// A record: immutable data carrier -> final fields, constructor, accessors, equals/hashCode, all generated. Ideal DTO.
// The Account ENTITY holds plaidAccessToken
// This record is the public contract, and the token simply as no field here -> it cannot leak, by construction
// Never serialize entities with secrets.
public record AccountResponse(Long id, String name, String type, String syncStatus){
     // Static factory: the one place that knows how to shrink entity -> DTO.
     // static (called on class), AccountResponse (return type), method name (from), parameter
    static AccountResponse from(Account account) {
        return new AccountResponse(account.getId(), account.getName(),
                account.getType(), account.getSyncStatus());
    }

}

//TLDR: AccountResponse is a separate, immutable record holding the four fields safe to expose over HTTP. Its static from method takes an Account entity — one row of the accounts table — and copies those four values into a new AccountResponse, leaving the secrets behind.