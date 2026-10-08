package io.github.emiliopg13.webhooks.common;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PingController {

	@GetMapping("/v1/ping")
	Map<String, String> ping() {
		return Map.of("status", "ok");
	}

}
