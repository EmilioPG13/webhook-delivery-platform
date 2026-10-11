package io.github.emiliopg13.webhooks.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;

import io.github.emiliopg13.webhooks.IntegrationTest;

/**
 * The paths Kubernetes probes. They must answer without credentials, and readiness must depend on the database
 * while liveness does not.
 */
class HealthProbesTest extends IntegrationTest {

	@Autowired
	private HealthEndpointGroups groups;

	@Test
	void livenessAndReadinessAnswerWithoutCredentials() throws Exception {
		mvc.perform(get("/actuator/health/liveness"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"));
		mvc.perform(get("/actuator/health/readiness"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void readinessChecksTheDatabaseAndLivenessDoesNot() {
		assertThat(groups.get("readiness").isMember("db")).isTrue();
		assertThat(groups.get("liveness").isMember("db")).isFalse();
	}

}
