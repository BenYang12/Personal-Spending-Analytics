package com.ledgerlens.backend.account;

import org.springframework.data.jpa.repository.JpaRepository;

// An interface with NO implementation anywhere
// Why?...
// At startup, Spring Data sees it, generates a working implementation (using Hibernate underneath), and registers it as a bean
// -> injectable wherever we declare it as a dependency.
// JpaRepository<Account, Long> = "rows of Account, primary key type Long";
// that alone gives us findAll(), findById(), save(), count(), delete()...
public interface AccountRepository extends JpaRepository<Account,Long>{} // JpaRepository<Account, Long> -> <entity, its primary key>, holds just account objects, nothing in it is a pair