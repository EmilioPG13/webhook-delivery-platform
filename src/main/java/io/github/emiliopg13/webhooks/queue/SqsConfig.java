package io.github.emiliopg13.webhooks.queue;

import java.net.URI;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

/** Only the worker role talks to the queue; the API role never builds an SQS client. */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
class SqsConfig {

	@Bean(destroyMethod = "close")
	SqsClient sqsClient(QueueProperties properties) {
		SqsClientBuilder builder = SqsClient.builder().region(Region.of(properties.region()));
		if (properties.endpoint() != null && !properties.endpoint().isBlank()) {
			// A local SQS stand-in: it checks nothing, but the SDK refuses to send an unsigned request.
			builder.endpointOverride(URI.create(properties.endpoint()))
				.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")));
		}
		return builder.build();
	}

}
