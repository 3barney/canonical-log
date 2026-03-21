package com.github.barney.canonicallog.app.service;

import com.github.barney.canonicallog.app.models.dto.RepaymentDto.RepaymentResponse;
import com.github.barney.canonicallog.app.models.dto.RepaymentDto.RepaymentRequest;

public interface RepaymentService {

    RepaymentResponse processRepayment(RepaymentRequest request);
}
