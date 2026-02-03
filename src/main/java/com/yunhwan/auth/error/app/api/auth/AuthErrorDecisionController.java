package com.yunhwan.auth.error.app.api.auth;

import com.yunhwan.auth.error.app.api.auth.dto.ApplyAnalysisDecisionRequest;
import com.yunhwan.auth.error.app.api.auth.dto.ApplyAnalysisDecisionResponse;
import com.yunhwan.auth.error.usecase.autherror.AuthErrorDecisionApplier;
import com.yunhwan.auth.error.app.api.auth.dto.ApplyAnalysisDecisionCommand;
import com.yunhwan.auth.error.app.api.auth.dto.ApplyAnalysisDecisionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@Profile("ops")
@ConditionalOnProperty(name = "auth-error.ops.decision.enabled", havingValue = "true")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth-errors")
public class AuthErrorDecisionController {

    private final AuthErrorDecisionApplier decisionApplier;

    @PostMapping("/{authErrorId}/decision")
    public ApplyAnalysisDecisionResponse applyDecision(
            @PathVariable Long authErrorId,
            @RequestBody ApplyAnalysisDecisionRequest req
    ) {
        ApplyAnalysisDecisionCommand cmd = ApplyAnalysisDecisionCommand.from(authErrorId, req);

        ApplyAnalysisDecisionResult r = decisionApplier.apply(cmd);
        return new ApplyAnalysisDecisionResponse(r.authErrorId(), r.status());
    }
}
