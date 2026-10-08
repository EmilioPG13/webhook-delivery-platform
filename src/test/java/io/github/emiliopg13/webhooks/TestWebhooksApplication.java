package io.github.emiliopg13.webhooks;

import org.springframework.boot.SpringApplication;

public class TestWebhooksApplication {

	public static void main(String[] args) {
		SpringApplication.from(WebhooksApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
