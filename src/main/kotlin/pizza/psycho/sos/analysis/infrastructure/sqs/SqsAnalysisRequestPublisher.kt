package pizza.psycho.sos.analysis.infrastructure.sqs

import io.awspring.cloud.sqs.operations.SqsTemplate
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import pizza.psycho.sos.analysis.application.port.AnalysisRequestPublishResult
import pizza.psycho.sos.analysis.application.port.AnalysisRequestPublisher
import pizza.psycho.sos.analysis.application.service.dto.SprintAnalysisInput
import pizza.psycho.sos.analysis.infrastructure.sqs.dto.SqsRequestQueueItem
import pizza.psycho.sos.common.support.log.loggerDelegate
import software.amazon.awssdk.core.exception.SdkException
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
    val sdkException = exception.findCause<SdkException>()

    return if (sdkException?.retryable() == true) {
        AnalysisRequestPublishResult.Failed.Retryable(
            message = "SQS 분석 요청 메시지 전송이 일시적으로 실패했습니다.",
            cause = exception,
        )
    } else {
        AnalysisRequestPublishResult.Failed.Permanent(
            message = "SQS 분석 요청 메시지를 전송할 수 없습니다.",
            cause = exception,
        )
    }
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
