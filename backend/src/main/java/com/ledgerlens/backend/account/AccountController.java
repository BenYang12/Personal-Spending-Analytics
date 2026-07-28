package com.ledgerlens.backend.account;

import java.util.List;
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

    public AccountController(AccountRepository accounts){
        this.accounts = accounts;
    }

    @GetMapping("/accounts") // full path: GET /api/accounts
    public List<AccountResponse> all(){
        // Entities in, DTOs out
        // .findAll is free method from JpaRepository, fires SELECT * FROM accounts and returns List<Account>
        // .stream wraps list in pipeline so I can chain transformations
        //.map(AccountResponse::from) -> map applies function to every element, function is static factory I wrote. 
        return accounts.findAll().stream().map(AccountResponse::from).toList();
    }
}
