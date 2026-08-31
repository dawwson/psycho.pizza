package pizza.psycho.sos.analysis.infrastructure.sqs

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pizza.psycho.sos.analysis.application.port.AnalysisRequestPublishResult
import software.amazon.awssdk.core.exception.SdkException
import java.util.concurrent.CompletionException

class SqsAnalysisRequestPublisherTests {
    @Test
    fun `AWS SDK가 재시도 가능하다고 판정한 예외를 일시 실패로 분류한다`() {
        val sdkException = mockk<SdkException>()
        every { sdkException.retryable() } returns true

        val result = classifyPublishFailure(CompletionException(sdkException))

        assertThat(result).isInstanceOf(AnalysisRequestPublishResult.Failed.Retryable::class.java)
        assertThat(result.cause).isInstanceOf(CompletionException::class.java)
        assertThat(result.message).isEqualTo("SQS 분석 요청 메시지 전송이 일시적으로 실패했습니다.")
    }

    @Test
    fun `재시도 가능 근거가 없는 예외를 영구 실패로 분류한다`() {
        val exception = IllegalArgumentException("메시지를 직렬화할 수 없습니다.")

        val result = classifyPublishFailure(exception)

        assertThat(result).isInstanceOf(AnalysisRequestPublishResult.Failed.Permanent::class.java)
        assertThat(result.cause).isSameAs(exception)
        assertThat(result.message).isEqualTo("SQS 분석 요청 메시지를 전송할 수 없습니다.")
    }
}
