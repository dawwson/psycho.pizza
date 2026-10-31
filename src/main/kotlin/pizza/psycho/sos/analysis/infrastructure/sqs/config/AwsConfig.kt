package pizza.psycho.sos.analysis.infrastructure.sqs.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import java.net.URI

@Configuration
class AwsConfig {
    @Value("\${aws.region:ap-northeast-2}")
    private lateinit var region: String

    @Bean
    fun sqsAsyncClient(
        @Value("\${sqs.endpoint:}") endpoint: String,
    ): SqsAsyncClient =
        SqsAsyncClient
            .builder()
            .region(Region.of(region))
            .credentialsProvider(DefaultCredentialsProvider.create())
            .apply {
                if (endpoint.isNotBlank()) {
                    endpointOverride(URI.create(endpoint))
                }
            }.build()
}
