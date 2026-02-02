package com.yunhwan.auth.error.app.api.auth;

import com.yunhwan.auth.error.usecase.autherror.cluster.AuthErrorClusterQueryService;
import com.yunhwan.auth.error.usecase.autherror.dto.AuthErrorClusterListResult;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth-error-clusters")
public class AuthErrorClusterController {

    private final AuthErrorClusterQueryService queryService;

    @GetMapping
    public AuthErrorClusterListResult list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "lastSeenAt") String sort,
            @RequestParam(defaultValue = "desc") String direction
    ) {
        Sort.Direction dir = "asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;

        // sort 파라미터는 API 표현용, 엔티티 컬럼명으로 매핑
        String sortProperty = switch (sort) {
            case "lastSeenAt" -> "lastSeenAt";
            case "totalCount" -> "totalCount";
            case "firstSeenAt" -> "firstSeenAt";
            default -> "lastSeenAt";
        };

        PageRequest pageable = PageRequest.of(page, size, Sort.by(dir, sortProperty));
        return queryService.list(pageable);
    }
}
