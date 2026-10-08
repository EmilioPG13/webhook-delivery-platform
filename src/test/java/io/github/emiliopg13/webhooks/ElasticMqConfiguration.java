package io.github.emiliopg13.webhooks;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/** A real SQS-compatible server for the tests, and the property that points the application at it. */
@TestConfiguration(proxyBeanMethods = false)
class ElasticMqConfiguration {

	private static final int PORT = 9324;

	@Bean
	GenericContainer<?> elasticMq() {
		return new GenericContainer<>(DockerImageName.parse("softwaremill/elasticmq-native:1.7.1"))
			.withExposedPorts(PORT)
			.waitingFor(Wait.forListeningPort());
	}

	@Bean
	DynamicPropertyRegistrar elasticMqEndpoint(GenericContainer<?> elasticMq) {
		return registry -> registry.add("webhooks.queue.endpoint",
				() -> "http://" + elasticMq.getHost() + ":" + elasticMq.getMappedPort(PORT));
	}

}
