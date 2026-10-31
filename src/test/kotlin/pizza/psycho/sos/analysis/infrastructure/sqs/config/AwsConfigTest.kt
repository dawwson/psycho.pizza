package pizza.psycho.sos.analysis.infrastructure.sqs.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import java.net.URI

class AwsConfigTest {
    private val contextRunner = ApplicationContextRunner().withUserConfiguration(AwsConfig::class.java)

    @Test
    fun `endpoint 설정이 비어 있으면 AWS 기본 endpoint를 사용한다`() {
        contextRunner.withPropertyValues("sqs.endpoint=").run { context ->
            val client = context.getBean(SqsAsyncClient::class.java)
            assertThat(client.serviceClientConfiguration().endpointOverride()).isEmpty
        }
    }

    @Test
    fun `로컬 endpoint 설정이 있으면 SQS client에 적용한다`() {
        contextRunner.withPropertyValues("sqs.endpoint=http://sqs:9324").run { context ->
            val client = context.getBean(SqsAsyncClient::class.java)
            assertThat(client.serviceClientConfiguration().endpointOverride()).contains(URI.create("http://sqs:9324"))
        }
    }
}
