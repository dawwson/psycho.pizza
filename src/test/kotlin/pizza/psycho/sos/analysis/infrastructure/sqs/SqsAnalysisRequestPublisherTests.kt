package pizza.psycho.sos.analysis.infrastructure.sqs

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import pizza.psycho.sos.analysis.application.port.AnalysisRequestPublishResult
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException
import software.amazon.awssdk.core.exception.RetryableException
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.sqs.model.SqsException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.CompletionException

class SqsAnalysisRequestPublisherTests {
    @ParameterizedTest
    @ValueSource(ints = [500, 502, 503, 504])
    fun `서버 오류는 재시도 가능으로 분류한다`(status: Int) {
        assertRetryable(SqsException.builder().statusCode(status).build())
    }

    @Test
    fun `429 응답은 재시도 가능으로 분류한다`() {
        assertRetryable(SqsException.builder().statusCode(429).build())
    }

    @Test
    fun `래핑된 SDK 개별 호출 타임아웃은 재시도 가능으로 분류한다`() {
        val exception = ApiCallAttemptTimeoutException.builder().message("attempt timed out").build()

        assertRetryable(CompletionException(exception))
    }

    @Test
    fun `400 응답의 요청 제한 오류도 재시도 가능으로 분류한다`() {
        val exception =
            SqsException
                .builder()
                .statusCode(400)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("RequestThrottled").build())
                .build()

        assertRetryable(exception)
    }

    @Test
    fun `래핑된 연결 실패와 타임아웃을 재시도 가능으로 분류한다`() {
        for (cause in listOf(ConnectException("connection refused"), SocketTimeoutException("read timed out"))) {
            assertRetryable(CompletionException(SdkClientException.create("send failed", cause)))
        }
    }

    @Test
    fun `명시적인 재시도 가능 SDK 예외를 재시도 가능으로 분류한다`() {
        assertRetryable(RetryableException.create("retry"))
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 403, 404])
    fun `잘못된 요청과 권한 오류는 실패로 분류한다`(status: Int) {
        assertPermanent(SqsException.builder().statusCode(status).build())
    }

    @Test
    fun `직렬화 오류와 근거 없는 클라이언트 오류는 실패로 분류한다`() {
        assertPermanent(IllegalArgumentException("메시지를 직렬화할 수 없습니다."))
        assertPermanent(SdkClientException.create("unknown client failure"))
    }

    private fun assertRetryable(exception: Throwable) {
        val result = classifyPublishFailure(exception)
        assertThat(result).isInstanceOf(AnalysisRequestPublishResult.Failed.Retryable::class.java)
        assertThat(result.cause).isSameAs(exception)
    }

    private fun assertPermanent(exception: Throwable) {
        val result = classifyPublishFailure(exception)
        assertThat(result).isInstanceOf(AnalysisRequestPublishResult.Failed.Permanent::class.java)
        assertThat(result.cause).isSameAs(exception)
    }
}
