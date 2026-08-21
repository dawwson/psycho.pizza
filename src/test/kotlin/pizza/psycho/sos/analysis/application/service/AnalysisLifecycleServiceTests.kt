package pizza.psycho.sos.analysis.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import pizza.psycho.sos.analysis.domain.entity.AnalysisReport
import pizza.psycho.sos.analysis.domain.entity.AnalysisRequest
import pizza.psycho.sos.analysis.domain.exception.AnalysisErrorCode
import pizza.psycho.sos.analysis.domain.vo.AnalysisRequestStatus
import pizza.psycho.sos.analysis.domain.vo.AnalysisTargetType
import pizza.psycho.sos.analysis.infrastructure.persistence.AnalysisReportRepository
import pizza.psycho.sos.analysis.infrastructure.persistence.AnalysisRequestRepository
import pizza.psycho.sos.common.handler.DomainException
import java.util.Optional
import java.util.UUID

/**
 * [AnalysisLifecycleService]가 repository에서 엔티티를 찾아 상태와 리포트를 함께 변경하는 동작을 고정한다.
 *
 * 상태 전이 규칙 자체는 AnalysisRequestTests의 책임이다. 여기서는 service가 올바른 엔티티를 조회하고
 * 도메인 메서드를 호출하며, 완료 결과를 연결된 AnalysisReport에 반영하는지를 검증한다.
 */
class AnalysisLifecycleServiceTests {
    @Nested
    inner class MarkRunning {
        @Test
        fun `QUEUED 요청을 조회해 RUNNING으로 변경한다`() {
            // Given: repository가 실제 DB 대신 준비한 QUEUED 요청을 반환한다고 가정한다.
            val fixture = LifecycleFixture()
            val request = createRequestWithId()
            every { fixture.analysisRequestRepository.findById(request.requiredId) } returns Optional.of(request)

            // When: 검증 대상 service를 실제로 실행한다.
            fixture.service.markRunning(request.requiredId)

            // Then: 실제 엔티티의 상태 변화와 repository 조회를 각각 확인한다.
            assertThat(request.status).isEqualTo(AnalysisRequestStatus.RUNNING)
            assertThat(request.startedAt).isNotNull()
            verify(exactly = 1) { fixture.analysisRequestRepository.findById(request.requiredId) }
        }
    }

    @Nested
    inner class Fail {
        @Test
        fun `RUNNING 요청을 FAILED로 변경하고 오류 메시지를 남긴다`() {
            val fixture = LifecycleFixture()
            val request = createRunningRequest()
            every { fixture.analysisRequestRepository.findById(request.requiredId) } returns Optional.of(request)

            fixture.service.fail(request.requiredId, "SQS 전송 실패")

            assertThat(request.status).isEqualTo(AnalysisRequestStatus.FAILED)
            assertThat(request.completedAt).isNotNull()
            assertThat(request.errorMessage).isEqualTo("SQS 전송 실패")
        }
    }

    @Nested
    inner class Complete {
        @Test
        fun `RUNNING 요청을 DONE으로 변경하고 리포트에 run ID와 분석 결과를 연결한다`() {
            val fixture = LifecycleFixture()
            val request = createRunningRequest()
            val report = createReport(request)
            every { fixture.analysisRequestRepository.findById(request.requiredId) } returns Optional.of(request)
            every { fixture.analysisReportRepository.findByAnalysisRequestId(request.requiredId) } returns report

            fixture.service.complete(
                jobId = request.requiredId,
                runId = "pickle-run-1",
                result = "스프린트 분석 결과",
            )

            // 하나의 완료 처리에서 요청 상태와 리포트 내용이 함께 바뀌어야 한다.
            assertThat(request.status).isEqualTo(AnalysisRequestStatus.DONE)
            assertThat(request.completedAt).isNotNull()
            assertThat(report.runId).isEqualTo("pickle-run-1")
            assertThat(report.aiInsight).isEqualTo("스프린트 분석 결과")

            // verifySequence는 요청을 확인한 뒤 그 요청에 연결된 리포트를 찾는 순서를 고정한다.
            verifySequence {
                fixture.analysisRequestRepository.findById(request.requiredId)
                fixture.analysisReportRepository.findByAnalysisRequestId(request.requiredId)
            }
        }

        @Test
        fun `연결된 리포트가 없으면 ANALYSIS_REPORT_NOT_FOUND 예외를 던진다`() {
            val fixture = LifecycleFixture()
            val request = createRunningRequest()
            every { fixture.analysisRequestRepository.findById(request.requiredId) } returns Optional.of(request)
            every { fixture.analysisReportRepository.findByAnalysisRequestId(request.requiredId) } returns null

            val exception =
                catchThrowableOfType(DomainException::class.java) {
                    fixture.service.complete(request.requiredId, "pickle-run-1", "분석 결과")
                }

            assertThat(exception.errorCode).isEqualTo(AnalysisErrorCode.ANALYSIS_REPORT_NOT_FOUND)
            verify(exactly = 1) { fixture.analysisReportRepository.findByAnalysisRequestId(request.requiredId) }

            // 이 단위 테스트는 Spring transaction rollback을 실행하지 않는다.
            // 실제 애플리케이션에서는 예외가 transaction 밖으로 전파되어 요청 상태 변경도 rollback 대상이 된다.
        }
    }

    @Test
    fun `분석 요청을 찾지 못하면 ANALYSIS_REQUEST_NOT_FOUND 예외를 던진다`() {
        val fixture = LifecycleFixture()
        val requestId = UUID.randomUUID()
        every { fixture.analysisRequestRepository.findById(requestId) } returns Optional.empty()

        val exception =
            catchThrowableOfType(DomainException::class.java) {
                fixture.service.markRunning(requestId)
            }

        assertThat(exception.errorCode).isEqualTo(AnalysisErrorCode.ANALYSIS_REQUEST_NOT_FOUND)
        // 요청 조회에서 중단됐으므로 리포트 repository에는 접근하지 않아야 한다.
        verify(exactly = 0) { fixture.analysisReportRepository.findByAnalysisRequestId(any()) }
    }
}

private class LifecycleFixture {
    // 검증 대상 service는 실제 객체이고 DB 접근만 mock repository로 대체한다.
    val analysisRequestRepository = mockk<AnalysisRequestRepository>()
    val analysisReportRepository = mockk<AnalysisReportRepository>()
    val service = AnalysisLifecycleService(analysisRequestRepository, analysisReportRepository)
}

private val AnalysisRequest.requiredId: UUID
    get() = requireNotNull(id)

private fun createRequestWithId(): AnalysisRequest =
    AnalysisRequest
        .create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        .apply { id = UUID.randomUUID() }

private fun createRunningRequest(): AnalysisRequest = createRequestWithId().apply { markAsRunning() }

private fun createReport(request: AnalysisRequest): AnalysisReport =
    AnalysisReport.create(
        analysisRequestId = request.requiredId,
        workspaceId = request.workspaceId,
        targetType = AnalysisTargetType.SPRINT,
        targetId = request.targetId,
        scoreTotal = 0,
        scoreVersion = "v2",
        categoryPenalties = "[]",
        penaltyDetails = "[]",
    )
