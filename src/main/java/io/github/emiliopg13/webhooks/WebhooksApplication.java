package io.github.emiliopg13.webhooks;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WebhooksApplication {

	public static void main(String[] args) {
		SpringApplication.run(WebhooksApplication.class, args);
	}

}
