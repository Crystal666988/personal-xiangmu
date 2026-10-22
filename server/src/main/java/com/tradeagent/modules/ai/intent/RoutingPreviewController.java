package com.tradeagent.modules.ai.intent;

import com.tradeagent.core.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Preview uses the user's request-scoped model configuration; tool policies remain backend-owned. */
@RestController
@RequestMapping("/api/v1/agent/routing")
public final class RoutingPreviewController {
    private final RoutingPreviewService service;

    public RoutingPreviewController(RoutingPreviewService service) { this.service = service; }

    public record PreviewRequest(String query, RoutingModelConfig modelConfig) {}

    @PostMapping("/preview")
    public ApiResponse<RoutingPreviewService.Preview> preview(@RequestBody(required = false) PreviewRequest request) {
        if (request == null || request.query() == null || request.query().isBlank()
                || request.query().length() > RoutingPreviewService.MAX_QUERY_LENGTH) {
            return ApiResponse.error(400, "query must contain 1 to 20000 characters");
        }
        return ApiResponse.success(service.preview(request.query(), request.modelConfig()));
    }
}
