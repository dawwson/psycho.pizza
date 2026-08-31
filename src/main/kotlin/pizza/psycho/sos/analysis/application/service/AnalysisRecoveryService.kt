package pizza.psycho.sos.analysis.application.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pizza.psycho.sos.analysis.application.policy.AnalysisDispatchPolicy
import pizza.psycho.sos.analysis.config.AnalysisRecoveryProperties
import pizza.psycho.sos.analysis.infrastructure.persistence.AnalysisRequestRepository
import pizza.psycho.sos.common.support.log.loggerDelegate
import java.time.Clock

@Service
class AnalysisRecoveryService(
    private val analysisRequestRepository: AnalysisRequestRepository,
    private val analysisDispatchPolicy: AnalysisDispatchPolicy,
    private val properties: AnalysisRecoveryProperties,
    private val clock: Clock,
) {
    private val log by loggerDelegate()

    @Transactional
    fun recoverStaleRequests() {
        val now = clock.instant()
        val staleBefore = now.minus(properties.staleTimeout)
        val staleRequests =
            analysisRequestRepository.claimStaleRunningRequests(
                staleBefore = staleBefore,
                batchSize = properties.batchSize,
            )

        staleRequests.forEach { request ->
            val analysisRequestId = requireNotNull(request.id) { "정체된 분석 요청을 복구하려면 ID가 필요합니다." }

            if (analysisDispatchPolicy.canRetry(request.attemptCount)) {
                request.rescheduleStaleRequest(now)
                log.info(
                    "정체된 분석 요청을 재발행 대기 상태로 복구했습니다. analysisRequestId={}, attemptCount={}",
                    analysisRequestId,
                    request.attemptCount,
                )
            } else {
                request.markAsFailed(STALE_MAX_ATTEMPTS_EXCEEDED_MESSAGE, now)
                log.error(
                    "정체된 분석 요청의 발행 시도 횟수가 소진되어 실패 처리했습니다. analysisRequestId={}, attemptCount={}",
                    analysisRequestId,
                    request.attemptCount,
                )
            }
        }
    }

    private companion object {
        const val STALE_MAX_ATTEMPTS_EXCEEDED_MESSAGE =
            "정체된 분석 요청이 최대 발행 시도 횟수를 사용해 종료되었습니다."
    }
}
