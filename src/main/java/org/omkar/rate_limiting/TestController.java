package org.omkar.rate_limiting;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class TestController {

    @GetMapping("/events")
    public ResponseEntity<Map<String, Object>> getEvents() {
        return ResponseEntity.ok(Map.of(
                "message", "Events fetched successfully",
                "timestamp", LocalDateTime.now(),
                "data", "Sample event data"
        ));
    }

    @GetMapping("/analytics")
    public ResponseEntity<Map<String, String>> testRateLimit() {
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Request processed successfully"
        ));
    }
}
