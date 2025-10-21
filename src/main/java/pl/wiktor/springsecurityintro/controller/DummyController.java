package pl.wiktor.springsecurityintro.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DummyController {

    @GetMapping("/dummy")
    public ResponseEntity<Map<String, Object>> getDummyData(
            @RequestParam(required = false) String param1,
            @RequestParam(required = false) String param2,
            @RequestHeader(value = "User-Agent", required = false) String userAgent,
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        Map<String, Object> response = new HashMap<>();
        response.put("message", "This is a dummy endpoint");
        response.put("timestamp", System.currentTimeMillis());
        response.put("param1", param1);
        response.put("param2", param2);
        response.put("userAgent", userAgent);
        response.put("hasAuthorization", authorization != null);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/dummy/{id}")
    public ResponseEntity<Map<String, Object>> getDummyDataById(@PathVariable String id) {
        Map<String, Object> response = new HashMap<>();
        response.put("message", "Dummy data for ID: " + id);
        response.put("id", id);
        response.put("timestamp", System.currentTimeMillis());

        return ResponseEntity.ok(response);
    }
}