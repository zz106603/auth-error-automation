package com.yunhwan.auth.error.app.autherror;


import com.yunhwan.auth.error.app.api.auth.dto.AuthErrorRecordRequest;
import com.yunhwan.auth.error.app.api.auth.dto.AuthErrorRecordResponse;
import com.yunhwan.auth.error.domain.autherror.AuthError;
import com.yunhwan.auth.error.usecase.autherror.AuthErrorWriter;
import com.yunhwan.auth.error.usecase.autherror.dto.AuthErrorWriteCommand;
import com.yunhwan.auth.error.usecase.autherror.dto.AuthErrorWriteResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthErrorFacade {

    private final AuthErrorWriter authErrorWriter;

    public AuthErrorRecordResponse record(AuthErrorRecordRequest req) {
        AuthErrorWriteCommand cmd = new AuthErrorWriteCommand(
                req.requestId(),
                req.occurredAt(),

                req.httpStatus(),

                req.httpMethod(),
                req.requestUri(),
                req.clientIp(),
                req.userAgent(),
                req.userId(),
                req.sessionId(),

                req.exceptionClass(),
                req.exceptionMessage(),
                req.rootCauseClass(),
                req.rootCauseMessage(),
                req.stacktrace()
        );
        AuthErrorWriteResult result = authErrorWriter.record(cmd);
        return new AuthErrorRecordResponse(result.authErrorId(), result.outboxId());
    }
}
