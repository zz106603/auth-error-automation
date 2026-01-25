package com.yunhwan.auth.error.app.api.auth;

import com.yunhwan.auth.error.app.api.auth.dto.AuthErrorRecordRequest;
import com.yunhwan.auth.error.app.api.auth.dto.AuthErrorRecordResponse;
import com.yunhwan.auth.error.app.autherror.AuthErrorFacade;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth-errors")
public class AuthController {

    private final AuthErrorFacade authErrorFacade;

    @PostMapping
    public ResponseEntity<AuthErrorRecordResponse> record(@Valid @RequestBody AuthErrorRecordRequest req) {
        return ResponseEntity.ok(authErrorFacade.record(req));
    }
}
