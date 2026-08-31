package pizza.psycho.sos.analysis.application.service

import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionOperations
import pizza.psycho.sos.analysis.application.policy.AnalysisDispatchPolicy
import pizza.psycho.sos.analysis.application.port.AnalysisRequestPublishResult
import pizza.psycho.sos.analysis.application.port.AnalysisRequestPublisher
import pizza.psycho.sos.analysis.domain.entity.AnalysisRequest
import pizza.psycho.sos.analysis.infrastructure.persistence.AnalysisRequestRepository
import pizza.psycho.sos.common.support.log.loggerDelegate
import java.time.Clock
import java.time.Instant

@Service
class AnalysisDispatcherService(
    private val analysisRequestRepository: AnalysisRequestRepository,
    private val sprintAnalysisMetricService: SprintAnalysisMetricService,
    private val analysisRequestPublisher: AnalysisRequestPublisher,
    private val analysisDispatchPolicy: AnalysisDispatchPolicy,
    private val tx: TransactionOperations,
    private val clock: Clock,
) {
    private val log by loggerDelegate()

    fun dispatchBatch(batchSize: Int) {
        require(batchSize > 0) { "분석 요청 발행 batch 크기는 1 이상이어야 합니다." }

        repeat(batchSize) {
            if (tx.execute { dispatchNextRequestIfPresent() } != true) {
                return
            }
        }
    }

    private fun dispatchNextRequestIfPresent(): Boolean {
        val now = clock.instant()
        val analysisRequest =
            analysisRequestRepository
                .claimDispatchableRequests(now = now, batchSize = 1)
                .firstOrNull()
                ?: return false

        val analysisRequestId = requireNotNull(analysisRequest.id) { "분석 요청을 발행하려면 ID가 필요합니다." }

        if (!analysisDispatchPolicy.canRetry(analysisRequest.attemptCount)) {
            analysisRequest.markAsFailed(MAX_ATTEMPTS_EXCEEDED_MESSAGE, now)
            log.error(
                "분석 요청 발행 시도 횟수를 모두 사용해 실패 처리했습니다. analysisRequestId={}, attemptCount={}",
                analysisRequestId,
                analysisRequest.attemptCount,
            )
            return true
        }

        analysisRequest.recordDispatchAttempt(now)
        log.info(
            "분석 요청 발행을 시작합니다. analysisRequestId={}, attemptCount={}",
            analysisRequestId,
            analysisRequest.attemptCount,
        )

        val input =
            try {
                sprintAnalysisMetricService.buildInput(
                    workspaceId = analysisRequest.workspaceId,
                    sprintId = analysisRequest.targetId,
                )
            } catch (exception: Exception) {
                analysisRequest.markAsFailed(INPUT_CREATION_FAILED_MESSAGE, now)
                log.error(
                    "분석 입력을 생성할 수 없어 실패 처리했습니다. analysisRequestId={}",
                    analysisRequestId,
                    exception,
                )
                return true
            }

        when (
            val result =
                analysisRequestPublisher.publish(
                    workspaceId = analysisRequest.workspaceId,
                    analysisRequestId = analysisRequestId,
                    payload = input,
                )
        ) {
            AnalysisRequestPublishResult.Published -> {
                analysisRequest.markAsRunning(now)
                log.info(
                    "분석 요청 발행에 성공했습니다. analysisRequestId={}, attemptCount={}",
                    analysisRequestId,
                    analysisRequest.attemptCount,
                )
            }

            is AnalysisRequestPublishResult.Failed.Retryable -> handleRetryableFailure(analysisRequest, result, now)

            is AnalysisRequestPublishResult.Failed.Permanent -> {
                analysisRequest.markAsFailed(result.message, now)
                log.error(
                    "분석 요청을 발행할 수 없어 실패 처리했습니다. analysisRequestId={}, attemptCount={}",
                    analysisRequestId,
                    analysisRequest.attemptCount,
                    result.cause,
                )
            }
        }

        return true
    }

    private fun handleRetryableFailure(
        analysisRequest: AnalysisRequest,
        failure: AnalysisRequestPublishResult.Failed.Retryable,
        failedAt: Instant,
    ) {
        val analysisRequestId = requireNotNull(analysisRequest.id)

        if (analysisDispatchPolicy.canRetry(analysisRequest.attemptCount)) {
            val nextRetryAt =
                analysisDispatchPolicy.calculateNextRetryAt(
                    attemptCount = analysisRequest.attemptCount,
                    failedAt = failedAt,
                )
            analysisRequest.scheduleDispatchRetry(nextRetryAt, failure.message)
            log.warn(
                "분석 요청 발행에 실패해 재시도를 예약했습니다. analysisRequestId={}, attemptCount={}, nextRetryAt={}",
                analysisRequestId,
                analysisRequest.attemptCount,
                nextRetryAt,
                failure.cause,
            )
        } else {
            analysisRequest.markAsFailed(MAX_ATTEMPTS_EXCEEDED_MESSAGE, failedAt)
            log.error(
                "분석 요청 발행 시도 횟수를 모두 사용해 실패 처리했습니다. analysisRequestId={}, attemptCount={}",
                analysisRequestId,
                analysisRequest.attemptCount,
                failure.cause,
            )
        }
    }

    private companion object {
        const val INPUT_CREATION_FAILED_MESSAGE = "분석 입력을 생성할 수 없어 요청을 종료했습니다."
        const val MAX_ATTEMPTS_EXCEEDED_MESSAGE =
            "분석 요청 발행이 일시적으로 실패했고 최대 시도 횟수를 모두 사용했습니다."
    }
}
