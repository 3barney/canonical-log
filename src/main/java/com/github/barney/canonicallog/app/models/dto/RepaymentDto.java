package com.github.barney.canonicallog.app.models.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public final class RepaymentDto {

    private RepaymentDto() {}

    public record RepaymentRequest(
            @NotBlank String loanId, @NotBlank String contractId, @NotBlank String loanProviderId,
            @NotBlank String msisdn, @NotNull @DecimalMin("0.01") BigDecimal amount, @NotBlank String currency
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RepaymentResponse(String loanId, String externalLoanId, String status, String message, String correlationId) {}

    public record LedgerServiceRepaymentRequest(String contractId, String msisdn, BigDecimal amount, String currency, String correlationId) {}

    public record LedgerServiceRepaymentResponse(String resultCode, String resultDesc, String referenceId) {
        public boolean isSuccess() {
            return "0".equals(resultCode);
        }
    }

    public record MsisdnQueryRequest(String contractId, String businessScope, String identifier) {}

    public record MsisdnQueryResponse(String resultCode, String resultDesc, String msisdn) {
        public boolean isSuccess() {
            return "0".equals(resultCode);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorResponse(String status, String errorCode, String message, String correlationId) {}
}
