package com.github.barney.canonicallog.app.controller;

import com.github.barney.canonicallog.app.models.dto.RepaymentDto;
import com.github.barney.canonicallog.app.models.dto.RepaymentDto.RepaymentResponse;
import com.github.barney.canonicallog.app.models.dto.RepaymentDto.RepaymentRequest;
import com.github.barney.canonicallog.app.service.RepaymentService;
import com.github.barney.canonicallog.lib.aspect.Tracked;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("api/v1/repayments")
public class RepaymentController {

    private final RepaymentService repaymentService;

    public RepaymentController(RepaymentService repaymentService) {
        this.repaymentService = repaymentService;
    }

    @PostMapping
    @Tracked("handleRepaymentRequest")
    public ResponseEntity<RepaymentResponse> initiateRepayment(
            @Valid @RequestBody RepaymentRequest request
    ) {

        RepaymentResponse response = repaymentService.processRepayment(request);

        return switch (response.status()) {
            case "SUCCESSFUL" -> ResponseEntity.ok(response);
            case "FAILED" -> ResponseEntity.unprocessableEntity().body(response);
            default -> ResponseEntity.badRequest().body(response);
        };
    }
}
