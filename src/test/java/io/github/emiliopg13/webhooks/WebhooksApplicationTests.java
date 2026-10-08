package io.github.emiliopg13.webhooks;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class WebhooksApplicationTests {

	@Test
	void contextLoads() {
	}

}
