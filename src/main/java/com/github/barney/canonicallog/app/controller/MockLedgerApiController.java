package com.github.barney.canonicallog.app.controller;

import com.github.barney.canonicallog.app.models.dto.RepaymentDto.MsisdnQueryRequest;
import com.github.barney.canonicallog.app.models.dto.RepaymentDto.MsisdnQueryResponse;
import com.github.barney.canonicallog.app.models.dto.RepaymentDto.LedgerServiceRepaymentResponse;
import com.github.barney.canonicallog.app.models.dto.RepaymentDto.LedgerServiceRepaymentRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;


/**
 * Mock external Ledger-Service API.
 *
 * Endpoints:
 *   POST /mock/ledger-service/msisdn-query   → Query MSISDN by contract ID
 *   POST /mock/ledger-service/repayment      → Process a repayment
 *
 * Behavior is controlled by the contract ID prefix:
 *   "FAIL_*" → Returns failure response
 *   "TIMEOUT_*" → Delays 5s then responds
 *   "ERROR_*" → Throws 500 error
 *   anything else → Success with 200-500ms delay
 */
@RestController
@RequestMapping("/mock/ledger-service")
public class MockLedgerApiController {

    @PostMapping("/msisdn-query")
    public ResponseEntity<MsisdnQueryResponse> queryMsisdn(
            @RequestBody MsisdnQueryRequest request
    ) throws InterruptedException {

        Thread.sleep(ThreadLocalRandom.current().nextInt(100, 300));

        String contractId = request.contractId();

        if (contractId != null && contractId.startsWith("FAIL_")) {
            return ResponseEntity.ok(new MsisdnQueryResponse("1001", "MSISDN not found for contract", null));
        }

        if (contractId != null && contractId.startsWith("ERROR_")) {
            return ResponseEntity.internalServerError().build();
        }

        if (contractId != null && contractId.startsWith("TIMEOUT_")) {
            Thread.sleep(5000);
        }

        return ResponseEntity.ok(new MsisdnQueryResponse("0", "Success", "+254712345678"));
    }

    @PostMapping("/repayment")
    public ResponseEntity<LedgerServiceRepaymentResponse> processRepayment(
            @RequestBody LedgerServiceRepaymentRequest request
    ) throws InterruptedException {

        Thread.sleep(ThreadLocalRandom.current().nextInt(200, 500));

        String contractId = request.contractId();

        if (contractId != null && contractId.startsWith("FAIL_")) {
            return ResponseEntity.ok(new LedgerServiceRepaymentResponse("2001", "Insufficient funds", null));
        }

        if (contractId != null && contractId.startsWith("ERROR_")) {
            throw new RuntimeException("Ledger Service internal processing error");
        }

        return ResponseEntity.ok(new LedgerServiceRepaymentResponse("0", "Repayment processed successfully", UUID.randomUUID().toString()));
    }
}
