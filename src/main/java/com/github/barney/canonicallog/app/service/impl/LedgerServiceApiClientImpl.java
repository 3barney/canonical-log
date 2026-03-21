package com.github.barney.canonicallog.app.service.impl;

import com.github.barney.canonicallog.app.models.dto.RepaymentDto.MsisdnQueryRequest;
import com.github.barney.canonicallog.app.models.dto.RepaymentDto.MsisdnQueryResponse;
import com.github.barney.canonicallog.app.models.dto.RepaymentDto.LedgerServiceRepaymentRequest;
import com.github.barney.canonicallog.app.models.dto.RepaymentDto.LedgerServiceRepaymentResponse;
import com.github.barney.canonicallog.app.service.LedgerServiceApiClient;
import com.github.barney.canonicallog.lib.aspect.Tracked;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class LedgerServiceApiClientImpl implements LedgerServiceApiClient {

    private final RestClient restClient;
    private final String baseurl;

    public LedgerServiceApiClientImpl(
            RestClient canonicalRestClient,
            @Value("${ledger-service.api.base-url:http://localhost:8080/mock/ledger-service}") String ledgerServiceBaseurl
    ) {
        this.restClient = canonicalRestClient;
        this.baseurl = ledgerServiceBaseurl;
    }

    @Tracked("ledgerQueryMsisdn")
    @Override
    public MsisdnQueryResponse ledgerQueryMsisdn(MsisdnQueryRequest request) {

        return restClient.post()
                .uri("%s/msisdn-query".formatted(this.baseurl))
                .body(request)
                .retrieve()
                .body(MsisdnQueryResponse.class);
    }

    @Tracked("ledgerProcessRepayment")
    @Override
    public LedgerServiceRepaymentResponse ledgerProcessRepayment(LedgerServiceRepaymentRequest request) {

        return restClient.post()
                .uri("%s/repayment".formatted(this.baseurl))
                .body(request)
                .retrieve()
                .body(LedgerServiceRepaymentResponse.class);
    }
}
