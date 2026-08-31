package pizza.psycho.sos.analysis.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import pizza.psycho.sos.analysis.application.policy.AnalysisDispatchPolicy
import pizza.psycho.sos.analysis.application.port.AnalysisRequestPublishResult
import pizza.psycho.sos.analysis.application.port.AnalysisRequestPublisher
import pizza.psycho.sos.analysis.application.service.dto.SprintAnalysisInput
import pizza.psycho.sos.analysis.config.AnalysisDispatchProperties
import pizza.psycho.sos.analysis.domain.entity.AnalysisRequest
import pizza.psycho.sos.analysis.domain.vo.AnalysisRequestStatus
import pizza.psycho.sos.analysis.infrastructure.persistence.AnalysisRequestRepository
import pizza.psycho.sos.common.handler.DomainException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class AnalysisDispatcherServiceTests {
    @Test
    fun `발행 가능한 요청을 하나씩 선점해 각각 발행한다`() {
        val fixture = DispatcherFixture()
        val firstRequest = createQueuedRequest()
        val secondRequest = createQueuedRequest()
        val firstInput = createInput(firstRequest)
        val secondInput = createInput(secondRequest)
        every { fixture.repository.claimDispatchableRequests(TEST_NOW, 1) } returnsMany
            listOf(listOf(firstRequest), listOf(secondRequest))
        every { fixture.metricService.buildInput(firstRequest.workspaceId, firstRequest.targetId) } returns firstInput
        every { fixture.metricService.buildInput(secondRequest.workspaceId, secondRequest.targetId) } returns secondInput
        every {
            fixture.publisher.publish(firstRequest.workspaceId, firstRequest.requiredId, firstInput)
        } returns AnalysisRequestPublishResult.Published
        every {
            fixture.publisher.publish(secondRequest.workspaceId, secondRequest.requiredId, secondInput)
        } returns AnalysisRequestPublishResult.Published

        fixture.service.dispatchBatch(batchSize = 2)

        assertThat(firstRequest.status).isEqualTo(AnalysisRequestStatus.RUNNING)
        assertThat(firstRequest.attemptCount).isEqualTo(1)
        assertThat(secondRequest.status).isEqualTo(AnalysisRequestStatus.RUNNING)
        assertThat(secondRequest.attemptCount).isEqualTo(1)
        verify(exactly = 2) { fixture.transactionOperations.execute<Boolean>(any()) }
    }

    @Test
    fun `일시적인 발행 실패에 다음 재시도를 예약하고 남은 요청을 처리한다`() {
        val fixture = DispatcherFixture()
        val failedRequest = createQueuedRequest()
        val successfulRequest = createQueuedRequest()
        val failedInput = createInput(failedRequest)
        val successfulInput = createInput(successfulRequest)
        every { fixture.repository.claimDispatchableRequests(TEST_NOW, 1) } returnsMany
            listOf(listOf(failedRequest), listOf(successfulRequest))
        every { fixture.metricService.buildInput(failedRequest.workspaceId, failedRequest.targetId) } returns failedInput
        every {
            fixture.metricService.buildInput(successfulRequest.workspaceId, successfulRequest.targetId)
        } returns successfulInput
        every {
            fixture.publisher.publish(failedRequest.workspaceId, failedRequest.requiredId, failedInput)
        } returns retryableFailure()
        every {
            fixture.publisher.publish(successfulRequest.workspaceId, successfulRequest.requiredId, successfulInput)
        } returns AnalysisRequestPublishResult.Published

        fixture.service.dispatchBatch(batchSize = 2)

        assertThat(failedRequest.status).isEqualTo(AnalysisRequestStatus.QUEUED)
        assertThat(failedRequest.attemptCount).isEqualTo(1)
        assertThat(failedRequest.nextRetryAt).isEqualTo(TEST_NOW.plusSeconds(5))
        assertThat(failedRequest.errorMessage).isEqualTo(RETRYABLE_FAILURE_MESSAGE)
        assertThat(successfulRequest.status).isEqualTo(AnalysisRequestStatus.RUNNING)
    }

    @Test
    fun `세 번째 일시적인 발행 실패는 FAILED로 종료한다`() {
        val fixture = DispatcherFixture()
        val request = createQueuedRequest().apply { recordTwoFailedAttempts() }
        val input = createInput(request)
        every { fixture.repository.claimDispatchableRequests(TEST_NOW, 1) } returns listOf(request)
        every { fixture.metricService.buildInput(request.workspaceId, request.targetId) } returns input
        every { fixture.publisher.publish(request.workspaceId, request.requiredId, input) } returns retryableFailure()

        fixture.service.dispatchBatch(batchSize = 1)

        assertThat(request.status).isEqualTo(AnalysisRequestStatus.FAILED)
        assertThat(request.attemptCount).isEqualTo(3)
        assertThat(request.nextRetryAt).isNull()
        assertThat(request.errorMessage).contains("최대 시도 횟수")
    }

    @Test
    fun `영구적인 발행 실패는 즉시 FAILED로 종료한다`() {
        val fixture = DispatcherFixture()
        val request = createQueuedRequest()
        val input = createInput(request)
        val failureMessage = "SQS 분석 요청 메시지를 전송할 수 없습니다."
        every { fixture.repository.claimDispatchableRequests(TEST_NOW, 1) } returns listOf(request)
        every { fixture.metricService.buildInput(request.workspaceId, request.targetId) } returns input
        every { fixture.publisher.publish(request.workspaceId, request.requiredId, input) } returns
            AnalysisRequestPublishResult.Failed.Permanent(failureMessage, IllegalArgumentException("잘못된 요청"))

        fixture.service.dispatchBatch(batchSize = 1)

        assertThat(request.status).isEqualTo(AnalysisRequestStatus.FAILED)
        assertThat(request.attemptCount).isEqualTo(1)
        assertThat(request.errorMessage).isEqualTo(failureMessage)
    }

    @Test
    fun `입력 생성에 실패하면 발행하지 않고 FAILED로 종료한다`() {
        val fixture = DispatcherFixture()
        val request = createQueuedRequest()
        every { fixture.repository.claimDispatchableRequests(TEST_NOW, 1) } returns listOf(request)
        every {
            fixture.metricService.buildInput(request.workspaceId, request.targetId)
        } throws DomainException(mockk(), "분석할 스프린트가 없습니다.")

        fixture.service.dispatchBatch(batchSize = 1)

        assertThat(request.status).isEqualTo(AnalysisRequestStatus.FAILED)
        assertThat(request.attemptCount).isEqualTo(1)
        assertThat(request.errorMessage).contains("분석 입력을 생성할 수 없어")
        verify(exactly = 0) { fixture.publisher.publish(any(), any(), any()) }
    }

    @Test
    fun `선점할 요청이 없으면 첫 transaction에서 반복을 종료한다`() {
        val fixture = DispatcherFixture()
        every { fixture.repository.claimDispatchableRequests(TEST_NOW, 1) } returns emptyList()

        fixture.service.dispatchBatch(batchSize = 10)

        verify(exactly = 1) { fixture.transactionOperations.execute<Boolean>(any()) }
        verify(exactly = 0) { fixture.metricService.buildInput(any(), any()) }
        verify(exactly = 0) { fixture.publisher.publish(any(), any(), any()) }
    }
}

private class DispatcherFixture {
    val repository = mockk<AnalysisRequestRepository>()
    val metricService = mockk<SprintAnalysisMetricService>()
    val publisher = mockk<AnalysisRequestPublisher>()
    val transactionOperations = mockk<TransactionOperations>()
    private val policy =
        AnalysisDispatchPolicy(
            AnalysisDispatchProperties(
                maxAttempts = 3,
                initialRetryDelay = Duration.ofSeconds(5),
            ),
        )
    private val clock: Clock = Clock.fixed(TEST_NOW, ZoneOffset.UTC)
    val service =
        AnalysisDispatcherService(
            repository,
            metricService,
            publisher,
            policy,
            transactionOperations,
            clock,
        )

    init {
        every { transactionOperations.execute<Boolean>(any()) } answers {
            firstArg<TransactionCallback<Boolean>>().doInTransaction(mockk())
        }
    }
}

private val TEST_NOW: Instant = Instant.parse("2026-08-31T01:00:00Z")
private const val RETRYABLE_FAILURE_MESSAGE = "SQS 분석 요청 메시지 전송이 일시적으로 실패했습니다."

private val AnalysisRequest.requiredId: UUID
    get() = requireNotNull(id)

private fun createQueuedRequest(): AnalysisRequest =
    AnalysisRequest
        .create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        .apply { id = UUID.randomUUID() }

private fun AnalysisRequest.recordTwoFailedAttempts() {
    recordDispatchAttempt(TEST_NOW.minusSeconds(15))
    scheduleDispatchRetry(TEST_NOW.minusSeconds(10), RETRYABLE_FAILURE_MESSAGE)
    recordDispatchAttempt(TEST_NOW.minusSeconds(10))
    scheduleDispatchRetry(TEST_NOW, RETRYABLE_FAILURE_MESSAGE)
}

private fun retryableFailure(): AnalysisRequestPublishResult.Failed.Retryable =
    AnalysisRequestPublishResult.Failed.Retryable(
        message = RETRYABLE_FAILURE_MESSAGE,
        cause = IllegalStateException("SQS 전송 실패"),
    )

private fun createInput(request: AnalysisRequest): SprintAnalysisInput =
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
