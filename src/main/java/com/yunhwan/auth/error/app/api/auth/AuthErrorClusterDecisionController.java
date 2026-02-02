package com.yunhwan.auth.error.app.api.auth;

import com.yunhwan.auth.error.app.api.auth.dto.*;
import com.yunhwan.auth.error.usecase.autherror.cluster.AuthErrorClusterDecisionApplier;
import com.yunhwan.auth.error.usecase.autherror.dto.ApplyClusterDecisionCommand;
import com.yunhwan.auth.error.usecase.autherror.dto.ApplyClusterDecisionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth-error-clusters")
public class AuthErrorClusterDecisionController {

    private final AuthErrorClusterDecisionApplier applier;

    @PostMapping("/{clusterId}/decision")
    public ApplyClusterDecisionResponse apply(
            @PathVariable Long clusterId,
            @RequestBody ApplyClusterDecisionRequest req
    ) {
        ApplyClusterDecisionCommand cmd = ApplyClusterDecisionCommand.from(clusterId, req);
        ApplyClusterDecisionResult r = applier.apply(cmd);

        return new ApplyClusterDecisionResponse(
                r.clusterId(),
                r.totalTargets(),
                r.appliedCount(),
                r.skippedCount(),
                r.failedCount()
        );
    }
}
