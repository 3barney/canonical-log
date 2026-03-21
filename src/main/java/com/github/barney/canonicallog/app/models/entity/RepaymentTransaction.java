package com.github.barney.canonicallog.app.models.entity;

import com.github.barney.canonicallog.app.listener.ApplicationEntityListener;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "repayment_transactions")
@EntityListeners(ApplicationEntityListener.class)
public class RepaymentTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String contractId;

    @Column(nullable = false)
    private String correlationId;

    @Column(nullable = false)
    private String loanId;

    private String loanProviderId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private TransactionState state;

    private String msisdn;

    @Column(length = 4000)
    private String ledgerServiceResponseCode;

    @Column(length = 4000)
    private String ledgerServiceResponseDesc;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public enum TransactionState {
        CREATED, PROCESSING, COMPLETED, FAILED
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getContractId() { return contractId; }
    public void setContractId(String contractId) { this.contractId = contractId; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public String getLoanId() { return loanId; }
    public void setLoanId(String loanId) { this.loanId = loanId; }
    public String getLoanProviderId() { return loanProviderId; }
    public void setLoanProviderId(String loanProviderId) { this.loanProviderId = loanProviderId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public TransactionState getState() { return state; }
    public void setState(TransactionState state) { this.state = state; }
    public String getMsisdn() { return msisdn; }
    public void setMsisdn(String msisdn) { this.msisdn = msisdn; }
    public String getLedgerServiceResponseCode() { return ledgerServiceResponseCode; }
    public void setLedgerServiceResponseCode(String ledgerServiceResponseCode) { this.ledgerServiceResponseCode = ledgerServiceResponseCode; }
    public String getLedgerServiceResponseDesc() { return ledgerServiceResponseDesc; }
    public void setLedgerServiceResponseDesc(String ledgerServiceResponseCode) { this.ledgerServiceResponseDesc = ledgerServiceResponseCode; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    @Override
    public String toString() {
        return "RepaymentTransaction{id='%s', loanId='%s', state=%s}".formatted(id, loanId, state);
    }
}
