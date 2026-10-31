package pizza.psycho.sos.analysis.infrastructure.sqs

import io.awspring.cloud.sqs.operations.SqsTemplate
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import pizza.psycho.sos.analysis.application.port.AnalysisRequestPublishResult
import pizza.psycho.sos.analysis.application.port.AnalysisRequestPublisher
import pizza.psycho.sos.analysis.application.service.dto.SprintAnalysisInput
import pizza.psycho.sos.analysis.infrastructure.sqs.dto.SqsRequestQueueItem
import pizza.psycho.sos.common.support.log.loggerDelegate
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.core.retry.RetryUtils
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.UUID

@Component
class SqsAnalysisRequestPublisher(
    private val template: SqsTemplate,
    @param:Value("\${sqs.request-queue-name}")
    private val queueName: String,
) : AnalysisRequestPublisher {
    private val log by loggerDelegate()

    override fun publish(
        workspaceId: UUID,
        analysisRequestId: UUID,
        input: SprintAnalysisInput,
    ): AnalysisRequestPublishResult {
        val payload =
            SqsRequestQueueItem(
                externalRequestId = analysisRequestId,
                resultFetchUrl = "https://your-domain/api/v1/analysis/requests/$analysisRequestId/result",
                openaiRequest = input,
                tenant = null,
                context = null,
            )

        log.info(
            "SQS 분석 요청 메시지를 전송합니다. queueName={}, workspaceId={}, analysisRequestId={}",
            queueName,
            workspaceId,
            analysisRequestId,
        )

        return runCatching {
            template.send {
                it.queue(queueName)
                it.payload(payload)
            }
        }.fold(
            onSuccess = {
                log.info(
                    "SQS 분석 요청 메시지 전송에 성공했습니다. queueName={}, workspaceId={}, analysisRequestId={}",
                    queueName,
                    workspaceId,
                    analysisRequestId,
                )
                AnalysisRequestPublishResult.Published
            },
            onFailure = { exception ->
                log.error(
                    "SQS 분석 요청 메시지 전송에 실패했습니다. queueName={}, workspaceId={}, analysisRequestId={}",
                    queueName,
                    workspaceId,
                    analysisRequestId,
                    exception,
                )
                classifyPublishFailure(exception)
            },
        )
    }
}

internal fun classifyPublishFailure(exception: Throwable): AnalysisRequestPublishResult.Failed {
    // 래핑된 예외에서 SDK 원인을 찾아 분류한다.
    val sdkException = exception.findCause<SdkException>()

    return if (sdkException?.isRetryablePublishFailure() == true) {
        AnalysisRequestPublishResult.Failed.Retryable(
            message = "SQS 분석 요청 메시지 전송이 일시적으로 실패했습니다.",
            cause = exception,
        )
    } else {
        // 재시도 근거가 없는 오류는 자동 재시도하지 않는다.
        AnalysisRequestPublishResult.Failed.Permanent(
            message = "SQS 분석 요청 메시지를 전송할 수 없습니다.",
            cause = exception,
        )
    }
}

// true면 재시도하고, false면 실패로 종료한다.
private fun SdkException.isRetryablePublishFailure(): Boolean {
    // SDK가 재시도 대상으로 표시한 오류는 재시도한다.
    if (retryable() || RetryUtils.isThrottlingException(this)) {
        return true
    }

    if (this is AwsServiceException) {
        // 서버 오류나 요청 제한은 잠시 뒤 성공할 수 있어 재시도한다.
        // 요청 제한은 400 응답일 수도 있으므로 오류 코드도 확인한다.
        return statusCode() in setOf(500, 502, 503, 504, 429) ||
            awsErrorDetails()?.errorCode() == "RequestThrottled"
    }

    if (this is SdkClientException) {
        // 연결 실패나 응답 지연은 일시적일 수 있어 재시도한다.
        return findCause<ConnectException>() != null ||
            findCause<SocketTimeoutException>() != null ||
            findCause<ApiCallAttemptTimeoutException>() != null
    }

    // 재시도 대상으로 정하지 않은 오류는 종료한다.
    return false
}

private inline fun <reified T : Throwable> Throwable.findCause(): T? {
    var current: Throwable? = this
    while (current != null) {
        if (current is T) {
            return current
        }
        current = current.cause
    }
    return null
}
