package pizza.psycho.sos.analysis.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import pizza.psycho.sos.analysis.domain.entity.AnalysisReport
import pizza.psycho.sos.analysis.domain.entity.AnalysisRequest
import pizza.psycho.sos.analysis.domain.exception.AnalysisErrorCode
import pizza.psycho.sos.analysis.domain.vo.AnalysisTargetType
import pizza.psycho.sos.analysis.infrastructure.persistence.AnalysisReportRepository
import pizza.psycho.sos.analysis.infrastructure.persistence.AnalysisRequestRepository
import pizza.psycho.sos.common.handler.DomainException
import java.time.Instant
import java.util.Optional
import java.util.UUID

class AnalysisRequestQueryServiceTests {
    @Nested
    inner class GetAnalysisRequestReport {
        @Test
        fun `같은 workspace의 요청이면 리포트를 반환한다`() {
            val fixture = QueryServiceFixture()
            val workspaceId = UUID.randomUUID()
            val analysisRequestId = UUID.randomUUID()
            val sprintId = UUID.randomUUID()
            val request = createRequest(workspaceId, sprintId, analysisRequestId)
            val report = createReport(workspaceId, sprintId, analysisRequestId)
            every {
                fixture.analysisRequestRepository.findByIdAndWorkspaceId(analysisRequestId, workspaceId)
            } returns Optional.of(request)
            every { fixture.analysisReportRepository.findByAnalysisRequestId(analysisRequestId) } returns report

            val result =
                fixture.service.getAnalysisRequestReport(
                    workspaceId = workspaceId,
                    analysisRequestId = analysisRequestId,
                )

            assertThat(result.workspaceId).isEqualTo(workspaceId)
            assertThat(result.sprintId).isEqualTo(sprintId)
            assertThat(result.analysisRequestId).isEqualTo(analysisRequestId)
            assertThat(result.status).isEqualTo(request.status)
            assertThat(result.totalScore).isEqualTo(42)
            assertThat(result.result).isEqualTo("분석 결과")
            assertThat(result.createdAt).isEqualTo(report.createdAt)
        }

        @Test
        fun `다른 workspace의 요청 ID이면 찾을 수 없는 요청으로 처리한다`() {
            val fixture = QueryServiceFixture()
            val workspaceId = UUID.randomUUID()
            val analysisRequestId = UUID.randomUUID()
            every {
                fixture.analysisRequestRepository.findByIdAndWorkspaceId(analysisRequestId, workspaceId)
            } returns Optional.empty()

            val exception =
                catchThrowableOfType(DomainException::class.java) {
                    fixture.service.getAnalysisRequestReport(
                        workspaceId = workspaceId,
                        analysisRequestId = analysisRequestId,
                    )
                }

            assertThat(exception.errorCode).isEqualTo(AnalysisErrorCode.ANALYSIS_REQUEST_NOT_FOUND)
            verify(exactly = 0) { fixture.analysisReportRepository.findByAnalysisRequestId(any()) }
        }

        @Test
        fun `존재하지 않는 요청 ID이면 찾을 수 없는 요청으로 처리한다`() {
            val fixture = QueryServiceFixture()
            val workspaceId = UUID.randomUUID()
            val analysisRequestId = UUID.randomUUID()
            every {
                fixture.analysisRequestRepository.findByIdAndWorkspaceId(analysisRequestId, workspaceId)
            } returns Optional.empty()

            val exception =
                catchThrowableOfType(DomainException::class.java) {
                    fixture.service.getAnalysisRequestReport(
                        workspaceId = workspaceId,
                        analysisRequestId = analysisRequestId,
                    )
                }

            assertThat(exception.errorCode).isEqualTo(AnalysisErrorCode.ANALYSIS_REQUEST_NOT_FOUND)
            verify(exactly = 0) { fixture.analysisReportRepository.findByAnalysisRequestId(any()) }
        }
    }
}

private class QueryServiceFixture {
    val analysisRequestRepository = mockk<AnalysisRequestRepository>()
    val analysisReportRepository = mockk<AnalysisReportRepository>()
    val service = AnalysisRequestQueryService(analysisRequestRepository, analysisReportRepository)
}

private fun createRequest(
    workspaceId: UUID,
    sprintId: UUID,
    analysisRequestId: UUID,
): AnalysisRequest =
    AnalysisRequest
        .create(
            workspaceId = workspaceId,
            sprintId = sprintId,
            memberId = UUID.randomUUID(),
        ).apply { id = analysisRequestId }

private fun createReport(
    workspaceId: UUID,
    sprintId: UUID,
    analysisRequestId: UUID,
): AnalysisReport =
    AnalysisReport
        .create(
            analysisRequestId = analysisRequestId,
            workspaceId = workspaceId,
            targetType = AnalysisTargetType.SPRINT,
            targetId = sprintId,
            scoreTotal = 42,
            scoreVersion = "v2",
            categoryPenalties = "[]",
            penaltyDetails = "[]",
        ).apply {
            attachAiInsight("분석 결과")
            createdAt = Instant.parse("2026-08-28T00:00:00Z")
        }
