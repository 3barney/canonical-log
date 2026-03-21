package com.github.barney.canonicallog.app.service;

import com.github.barney.canonicallog.app.models.dto.RepaymentDto.MsisdnQueryResponse;
import com.github.barney.canonicallog.app.models.dto.RepaymentDto.MsisdnQueryRequest;
import com.github.barney.canonicallog.app.models.dto.RepaymentDto.LedgerServiceRepaymentResponse;
import com.github.barney.canonicallog.app.models.dto.RepaymentDto.LedgerServiceRepaymentRequest;

public interface LedgerServiceApiClient {

    MsisdnQueryResponse ledgerQueryMsisdn(MsisdnQueryRequest request);

    LedgerServiceRepaymentResponse ledgerProcessRepayment(LedgerServiceRepaymentRequest request);
}
