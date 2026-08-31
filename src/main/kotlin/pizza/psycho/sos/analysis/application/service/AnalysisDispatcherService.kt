package pizza.psycho.sos.analysis.application.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pizza.psycho.sos.analysis.application.port.AnalysisRequestPublishResult
import pizza.psycho.sos.analysis.application.port.AnalysisRequestPublisher
import pizza.psycho.sos.analysis.domain.entity.AnalysisRequest
import pizza.psycho.sos.analysis.infrastructure.persistence.AnalysisRequestRepository
import pizza.psycho.sos.common.support.log.loggerDelegate

@Service
class AnalysisDispatcherService(
    private val analysisRequestRepository: AnalysisRequestRepository,
    private val sprintAnalysisMetricService: SprintAnalysisMetricService,
    private val analysisRequestPublisher: AnalysisRequestPublisher,
) {
    private val log by loggerDelegate()

    @Transactional
    fun dispatchBatch(batchSize: Int) {
        val claimedRequests = analysisRequestRepository.claimQueued(batchSize)

        claimedRequests.forEach { request ->
            try {
                dispatch(request)
            } catch (exception: Exception) {
                // 오류 분류와 제한 retry는 #21에서 추가한다. 현재는 QUEUED로 남겨 다시 발견되게 한다.
                log.error("❌ Analysis dispatch failed: jobId=${request.id}", exception)
            }
        }
    }

    private fun dispatch(analysisRequest: AnalysisRequest) {
        val analysisRequestId = requireNotNull(analysisRequest.id) { "Analysis request ID is required for dispatch" }

        log.info("🍕 Start analysis dispatch: jobId=$analysisRequestId")

        // TODO: score 계산 -> report 저장
        val input =
            sprintAnalysisMetricService.buildInput(
                workspaceId = analysisRequest.workspaceId,
                sprintId = analysisRequest.targetId,
            )

        when (
            val result =
                analysisRequestPublisher.publish(
                    workspaceId = analysisRequest.workspaceId,
                    analysisRequestId = analysisRequestId,
                    payload = input,
                )
        ) {
            AnalysisRequestPublishResult.Published -> Unit
            is AnalysisRequestPublishResult.Failed -> throw result.cause
        }

        // RUNNING은 처리 시작이 아니라 Pickle에 전달되어 결과를 기다리는 상태를 뜻한다.
        analysisRequest.markAsRunning()
        log.info("🚀 Successfully sent analysis request to SQS: jobId=$analysisRequestId")
    }
}
