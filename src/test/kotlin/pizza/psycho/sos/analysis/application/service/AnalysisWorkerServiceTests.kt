package pizza.psycho.sos.analysis.application.service

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import pizza.psycho.sos.analysis.application.port.RequestQueueProducer
import pizza.psycho.sos.analysis.application.service.dto.SprintAnalysisInput
import pizza.psycho.sos.analysis.domain.entity.AnalysisRequest
import java.util.UUID

/**
 * [AnalysisWorkerService]의 orchestration 순서와 단계별 실패 기록 형식을 고정한다.
 * 협력 객체는 mock으로 격리하고 호출 순서, 실패 지점 기록, 원본 예외 재전파를 검증한다.
 */
class AnalysisWorkerServiceTests {
    @Test
    fun `분석 요청을 RUNNING으로 변경한 뒤 metric 입력을 생성해 SQS로 전송한다`() {
        // Given: worker가 의존하는 네 협력 객체의 성공 응답을 준비한다.
        val fixture = WorkerFixture()
        val jobId = UUID.randomUUID()
        val request = createAnalysisRequest()
        val input = createSprintAnalysisInput(request)
        fixture.stubSuccessfulPipeline(jobId, request, input)

        // When: orchestration을 담당하는 worker만 실제로 실행한다.
        fixture.service.processAnalysisJob(jobId)

        // Then: verifySequence로 "무엇을 호출했는지"뿐 아니라 "어떤 순서로 호출했는지" 확인한다.
        verifySequence {
            fixture.lifecycleService.markRunning(jobId)
            fixture.requestService.getAnalysisRequest(jobId)
            fixture.metricService.buildInput(request.workspaceId, request.targetId)
            fixture.requestQueueProducer.send(request.workspaceId, jobId, input)
        }
        verify(exactly = 0) { fixture.lifecycleService.fail(any(), any()) }
    }

    @Nested
    inner class FailureHandling {
        @ParameterizedTest(name = "{0} 단계 실패를 기록하고 원래 예외를 던진다")
        @EnumSource(FailurePoint::class)
        fun `단계별 실패를 기록하고 원래 예외를 던진다`(failurePoint: FailurePoint) {
            // @EnumSource가 FailurePoint의 네 값을 주입하므로 같은 검증을 단계마다 복사하지 않아도 된다.
            val fixture = WorkerFixture()
            val jobId = UUID.randomUUID()
            val request = createAnalysisRequest()
            val input = createSprintAnalysisInput(request)
            val originalException = IllegalStateException("${failurePoint.errorStep} 오류")
            // 먼저 기본 성공 경로를 준비한 뒤, 이번 케이스의 한 단계만 예외를 던지도록 덮어쓴다.
            fixture.stubSuccessfulPipeline(jobId, request, input)
            fixture.stubFailure(failurePoint, jobId, request, originalException)

            val thrown =
                catchThrowableOfType(IllegalStateException::class.java) {
                    fixture.service.processAnalysisJob(jobId)
                }

            // wrapper로 바꾸지 않아 실제 실패 원인과 stack trace를 그대로 관찰할 수 있어야 한다.
            assertThat(thrown).isSameAs(originalException)
            // 실패 원인 저장은 상태 검증이 아니라 협력 객체와의 상호작용 검증이다.
            verify(exactly = 1) {
                fixture.lifecycleService.fail(
                    jobId,
                    "FAILED_AT=${failurePoint.errorStep} message=${failurePoint.errorStep} 오류",
                )
            }
            fixture.verifyNoCallsAfter(failurePoint, jobId, request)
        }
    }

    enum class FailurePoint(
        val errorStep: String,
    ) {
        MARK_RUNNING("MARK_RUNNING"),
        LOAD_ANALYSIS_REQUEST("LOAD_ANALYSIS_REQUEST"),
        CALCULATE_METRICS("CALCULATE_METRICS"),
        SEND_MESSAGE_TO_SQS("SEND_MESSAGE_TO_SQS"),
    }
}

private class WorkerFixture {
    // 검증 대상인 AnalysisWorkerService를 제외한 협력 객체는 모두 mock이다.
    val lifecycleService = mockk<AnalysisLifecycleService>()
    val metricService = mockk<SprintAnalysisMetricService>()
    val requestService = mockk<AnalysisRequestService>()
    val requestQueueProducer = mockk<RequestQueueProducer>()
    val service = AnalysisWorkerService(lifecycleService, metricService, requestService, requestQueueProducer)

    fun stubSuccessfulPipeline(
        jobId: UUID,
        request: AnalysisRequest,
        input: SprintAnalysisInput,
    ) {
        // justRun은 Unit 반환 메서드, every/returns는 값을 반환하는 메서드의 정상 동작을 준비한다.
        justRun { lifecycleService.markRunning(jobId) }
        every { requestService.getAnalysisRequest(jobId) } returns request
        every { metricService.buildInput(request.workspaceId, request.targetId) } returns input
        justRun { requestQueueProducer.send(request.workspaceId, jobId, input) }
        justRun { lifecycleService.fail(any(), any()) }
    }

    fun stubFailure(
        failurePoint: AnalysisWorkerServiceTests.FailurePoint,
        jobId: UUID,
        request: AnalysisRequest,
        exception: RuntimeException,
    ) {
        // throws는 해당 mock 호출 시 준비한 예외를 발생시켜 실제 장애 지점을 재현한다.
        when (failurePoint) {
            AnalysisWorkerServiceTests.FailurePoint.MARK_RUNNING -> {
                every { lifecycleService.markRunning(jobId) } throws exception
            }
            AnalysisWorkerServiceTests.FailurePoint.LOAD_ANALYSIS_REQUEST -> {
                every { requestService.getAnalysisRequest(jobId) } throws exception
            }
            AnalysisWorkerServiceTests.FailurePoint.CALCULATE_METRICS -> {
                every { metricService.buildInput(request.workspaceId, request.targetId) } throws exception
            }
            AnalysisWorkerServiceTests.FailurePoint.SEND_MESSAGE_TO_SQS -> {
                every { requestQueueProducer.send(request.workspaceId, jobId, any()) } throws exception
            }
        }
    }

    /**
     * 실패 지점 뒤의 비싼 metric 계산이나 외부 SQS 호출이 이어지지 않는지 확인한다.
     * `exactly = 0`은 단순히 결과가 없는 것이 아니라 해당 협력 객체를 아예 호출하지 않았음을 뜻한다.
     */
    fun verifyNoCallsAfter(
        failurePoint: AnalysisWorkerServiceTests.FailurePoint,
        jobId: UUID,
        request: AnalysisRequest,
    ) {
        when (failurePoint) {
            AnalysisWorkerServiceTests.FailurePoint.MARK_RUNNING -> {
                verify(exactly = 0) { requestService.getAnalysisRequest(jobId) }
                verify(exactly = 0) { metricService.buildInput(any(), any()) }
                verify(exactly = 0) { requestQueueProducer.send(any(), any(), any()) }
            }
            AnalysisWorkerServiceTests.FailurePoint.LOAD_ANALYSIS_REQUEST -> {
                verify(exactly = 0) { metricService.buildInput(any(), any()) }
                verify(exactly = 0) { requestQueueProducer.send(any(), any(), any()) }
            }
            AnalysisWorkerServiceTests.FailurePoint.CALCULATE_METRICS -> {
                verify(exactly = 1) { requestService.getAnalysisRequest(jobId) }
                verify(exactly = 0) { requestQueueProducer.send(any(), any(), any()) }
            }
            AnalysisWorkerServiceTests.FailurePoint.SEND_MESSAGE_TO_SQS -> {
                verify(exactly = 1) { requestService.getAnalysisRequest(jobId) }
                verify(exactly = 1) { metricService.buildInput(request.workspaceId, request.targetId) }
                verify(exactly = 1) { requestQueueProducer.send(request.workspaceId, jobId, any()) }
            }
        }
    }
}

private fun createAnalysisRequest(): AnalysisRequest = AnalysisRequest.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())

private fun createSprintAnalysisInput(request: AnalysisRequest): SprintAnalysisInput =
    SprintAnalysisInput(
        schemaVersion = "0.1.0",
        context =
            SprintAnalysisInput.Context(
                request.workspaceId,
                SprintAnalysisInput.Context.Sprint(request.targetId, "Sprint 1", 14, 4),
            ),
        summary =
            SprintAnalysisInput.Summary(
                SprintAnalysisInput.Summary.StatusSnapshot(1, 1, 2, 0),
            ),
        metrics =
            SprintAnalysisInput.Metrics(
                SprintAnalysisInput.Metrics.Completion(1),
                SprintAnalysisInput.Metrics.Stability(1, 0),
                SprintAnalysisInput.Metrics.Flow(1, 0, 1, 0),
            ),
    )
