// Notes
// A @RestController is a bean whose methods handle HTTP requests: @GetMapping("/api/accounts") maps a url to a method
// DTO (data transfer object) -> Java records are ideal DTOs.
package com.ledgerlens.backend.account;

import java.util.List;

// A record: immutable data carrier -> final fields, constructor, accessors, equals/hashCode, all generated. Ideal DTO.
// The Account ENTITY holds plaidAccessToken
// This record is the public contract, and the token simply as no field here -> it cannot leak, by construction
// Never serialize entities with secrets.
//
// transactionCount and months were added for the dashboard's pickers. They let
// the frontend do two things in ONE request that would otherwise need many:
// filter out accounts too thin to be worth showing, and populate the month
// dropdown with only months that actually have data. Without them the UI would
// either guess a month range and render empty views, or fire a dozen probing
// requests per page load to find out.
public record AccountResponse(Long id, String name, String type, String syncStatus,
                              long transactionCount, List<String> months) {
     // Static factory: the one place that knows how to shrink entity -> DTO.
     // static (called on class), AccountResponse (return type), method name (from), parameter
    static AccountResponse from(Account account, long transactionCount, List<String> months) {
        return new AccountResponse(account.getId(), account.getName(),
                account.getType(), account.getSyncStatus(), transactionCount, months);
    }

}

//TLDR: AccountResponse is a separate, immutable record holding the fields safe to expose over HTTP. Its static from method takes an Account entity — one row of the accounts table — and copies those values into a new AccountResponse, leaving the secrets behind.
