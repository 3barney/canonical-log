package com.github.barney.canonicallog.app.repository;

import com.github.barney.canonicallog.app.models.entity.RepaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RepaymentTransactionRepository extends JpaRepository<RepaymentTransaction, String> {

    Optional<RepaymentTransaction> findByCorrelationId(String correlationId);

    Optional<RepaymentTransaction> findByLoanId(String loanId);
}
