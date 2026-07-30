package com.ledgerlens.backend.plaid;

import com.plaid.client.ApiClient;
import com.plaid.client.request.PlaidApi;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Plaid background
// 1. Item -> one user's login at one institution (one item may expose several accounts)
// 2. access_token -> long-lived credential for one itme
// 3. link_token -> short lived token that initializes the Link UI
// 4. public_token -> what Link hands back after user authenticates

// backend  /link/token/create        → link_token
// frontend Link UI (user logs in)    → public_token
// backend  /item/public_token/exchange → access_token   ← store this
// backend  /transactions/sync (access_token) → transactions


// @Configuration: a class whose @Bean methods CREATE beans.
// this is how I register a third-party class (PlaidApi lives in Plaid's jar, so I can't put @Component on it)
// I construct it; Spring owns and injects it

@Configuration
public class PlaidConfig{
    // used to inject external configurations (from property files, environment variables, or system properties) directly into fields, constructor arguments, or method parameters within Spring-managed beans.
    @Value("${ledgerlens.plaid.client-id}")
    private String clientId;

    @Value("${ledgerlens.plaid.secret}")
    private String secret;

    // Whatever this method RETURNS becomes a singleton bean
    @Bean
    public PlaidApi plaidApi(){
        // The SDK takes credentials as a map and attaches them to every request
        // Map.of() creates an immutable hashmap
        ApiClient apiClient = new ApiClient(Map.of(
                "clientId", clientId,
                "secret", secret));

        // Points the client at sandbox.plaid.com. Swapping this constant is
        // the ONLY code change needed to go to production
        apiClient.setPlaidAdapter(ApiClient.Sandbox);

        // Retrofit builds an implementation of the PlaidApi interface.
        return apiClient.createService(PlaidApi.class);

    }


}