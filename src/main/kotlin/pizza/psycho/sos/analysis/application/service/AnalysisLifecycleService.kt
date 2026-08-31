package pizza.psycho.sos.analysis.application.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pizza.psycho.sos.analysis.domain.entity.AnalysisRequest
import pizza.psycho.sos.analysis.domain.exception.AnalysisErrorCode
import pizza.psycho.sos.analysis.infrastructure.persistence.AnalysisReportRepository
import pizza.psycho.sos.analysis.infrastructure.persistence.AnalysisRequestRepository
import pizza.psycho.sos.common.handler.DomainException
import java.time.Clock
import java.util.UUID

/*
 * AnalysisLifecycleService
 * - 상태 전이 / persistence
 */
@Service
class AnalysisLifecycleService(
    private val analysisRequestRepository: AnalysisRequestRepository,
    private val analysisReportRepository: AnalysisReportRepository,
    private val clock: Clock,
) {
    @Transactional
    fun complete(
        jobId: UUID,
        runId: String,
        result: String,
    ) {
        // running -> done
        val analysisRequest = getAnalysisRequestEntity(jobId)
        analysisRequest.markAsCompleted(clock.instant())

        // save report
        val analysisReport =
            analysisReportRepository.findByAnalysisRequestId(jobId)
                ?: throw DomainException(AnalysisErrorCode.ANALYSIS_REPORT_NOT_FOUND)

        analysisReport.attachRunId(runId)
        analysisReport.attachAiInsight(result)
    }

    private fun getAnalysisRequestEntity(id: UUID): AnalysisRequest =
        analysisRequestRepository
            .findById(id)
            .orElseThrow { DomainException(AnalysisErrorCode.ANALYSIS_REQUEST_NOT_FOUND) }
}
