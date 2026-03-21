package com.github.barney.canonicallog.app.service.impl;

import com.github.barney.canonicallog.app.models.dto.RepaymentDto;
import com.github.barney.canonicallog.app.models.dto.RepaymentDto.MsisdnQueryResponse;
import com.github.barney.canonicallog.app.models.dto.RepaymentDto.MsisdnQueryRequest;
import com.github.barney.canonicallog.app.models.dto.RepaymentDto.RepaymentRequest;
import com.github.barney.canonicallog.app.models.dto.RepaymentDto.RepaymentResponse;
import com.github.barney.canonicallog.app.models.dto.RepaymentDto.LedgerServiceRepaymentResponse;
import com.github.barney.canonicallog.app.models.dto.RepaymentDto.LedgerServiceRepaymentRequest;
import com.github.barney.canonicallog.app.models.entity.RepaymentTransaction;
import com.github.barney.canonicallog.app.repository.RepaymentTransactionRepository;
import com.github.barney.canonicallog.app.service.LedgerServiceApiClient;
import com.github.barney.canonicallog.app.service.RepaymentService;
import com.github.barney.canonicallog.lib.aspect.Tracked;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class RepaymentServiceImpl implements RepaymentService {

    private final LedgerServiceApiClient apiClient;
    private final RepaymentTransactionRepository repository;

    public RepaymentServiceImpl(LedgerServiceApiClient ledgerServiceApiClient, RepaymentTransactionRepository repaymentRepository) {
        this.apiClient = ledgerServiceApiClient;
        this.repository = repaymentRepository;
    }

    @Tracked("processRepayment")
    @Transactional
    @Override
    public RepaymentResponse processRepayment(RepaymentRequest request) {

        String correlationId = UUID.randomUUID().toString();
        String contractId = request.contractId();

        MsisdnQueryResponse msisdnQueryResponse = queryMsisdn(contractId);

        if (!msisdnQueryResponse.isSuccess()) {

            RepaymentTransaction transaction = createTransaction(request, contractId, correlationId);
            transaction.setState(RepaymentTransaction.TransactionState.FAILED);
            transaction.setLedgerServiceResponseCode(msisdnQueryResponse.resultCode());
            transaction.setLedgerServiceResponseDesc(msisdnQueryResponse.resultDesc());
            repository.save(transaction);

            return new RepaymentResponse(
                    request.loanId(), request.contractId(), "FAILED",
                    "MSISDN lookup failed: " + msisdnQueryResponse.resultDesc(), correlationId
            );
        }

        RepaymentTransaction transaction = createTransaction(request, contractId, correlationId);
        transaction.setMsisdn(msisdnQueryResponse.msisdn());
        transaction.setState(RepaymentTransaction.TransactionState.PROCESSING);
        repository.save(transaction);

        // call ledger repayment service
        LedgerServiceRepaymentResponse ledgerResponse = processLedgerRepayment(
                contractId, msisdnQueryResponse.msisdn(), request, correlationId
        );

        if (ledgerResponse.isSuccess()) {
            transaction.setState(RepaymentTransaction.TransactionState.COMPLETED);
            transaction.setLedgerServiceResponseCode(ledgerResponse.resultCode());
            transaction.setLedgerServiceResponseDesc(ledgerResponse.resultDesc());
            repository.save(transaction);

            return new RepaymentResponse(
                    request.loanId(),contractId, "SUCCESSFUL", "Repayment processed", correlationId
            );
        } else {
            transaction.setState(RepaymentTransaction.TransactionState.FAILED);
            transaction.setLedgerServiceResponseCode(ledgerResponse.resultCode());
            transaction.setLedgerServiceResponseDesc(ledgerResponse.resultDesc());
            repository.save(transaction);

            return new RepaymentResponse(
                    request.loanId(), contractId, "FAILED", ledgerResponse.resultDesc(), correlationId
            );
        }
    }

    @Tracked(value = "processLedgerRepayment", maskArgs = {"msisdn"})
    private LedgerServiceRepaymentResponse processLedgerRepayment(String contractId, String msisdn, RepaymentRequest request, String correlationId) {
        return apiClient.ledgerProcessRepayment(new LedgerServiceRepaymentRequest(
                contractId, msisdn, request.amount(), request.currency(), correlationId)
        );
    }

    @Tracked(value = "queryMsisdn", maskArgs = {"contractId"})
    private MsisdnQueryResponse queryMsisdn(String contractId) {
        return apiClient.ledgerQueryMsisdn(new MsisdnQueryRequest(contractId, "Loan_Repayment", "LEDGER_SERVICE"));
    }

    private RepaymentTransaction createTransaction(RepaymentRequest request, String contractId, String correlationId) {

        RepaymentTransaction repaymentTransaction = new RepaymentTransaction();
        repaymentTransaction.setContractId(contractId);
        repaymentTransaction.setCorrelationId(correlationId);
        repaymentTransaction.setLoanId(request.loanId());
        repaymentTransaction.setLoanProviderId(request.loanProviderId());
        repaymentTransaction.setAmount(request.amount());
        repaymentTransaction.setCurrency(request.currency());
        repaymentTransaction.setState(RepaymentTransaction.TransactionState.CREATED);
        return repaymentTransaction;
    }
}
