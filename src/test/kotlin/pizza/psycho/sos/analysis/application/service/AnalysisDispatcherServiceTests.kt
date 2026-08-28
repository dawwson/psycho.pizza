package pizza.psycho.sos.analysis.application.service

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pizza.psycho.sos.analysis.application.port.RequestQueueProducer
import pizza.psycho.sos.analysis.application.service.dto.SprintAnalysisInput
import pizza.psycho.sos.analysis.domain.entity.AnalysisRequest
import pizza.psycho.sos.analysis.domain.vo.AnalysisRequestStatus
import pizza.psycho.sos.analysis.infrastructure.persistence.AnalysisRequestRepository
import java.util.UUID

/** [AnalysisDispatcherService]가 선점한 batch를 서로 독립적으로 처리하는지 검증한다. */
class AnalysisDispatcherServiceTests {
    @Test
    fun `선점한 요청을 모두 SQS로 전송하고 RUNNING으로 변경한다`() {
        val fixture = DispatcherFixture()
        val firstRequest = createQueuedRequest()
        val secondRequest = createQueuedRequest()
        val firstInput = createInput(firstRequest)
        val secondInput = createInput(secondRequest)
        every { fixture.repository.claimQueued(2) } returns listOf(firstRequest, secondRequest)
        every { fixture.metricService.buildInput(firstRequest.workspaceId, firstRequest.targetId) } returns firstInput
        every { fixture.metricService.buildInput(secondRequest.workspaceId, secondRequest.targetId) } returns secondInput
        justRun { fixture.requestQueueProducer.send(firstRequest.workspaceId, firstRequest.requiredId, firstInput) }
        justRun { fixture.requestQueueProducer.send(secondRequest.workspaceId, secondRequest.requiredId, secondInput) }

        fixture.service.dispatchBatch(batchSize = 2)

        assertThat(firstRequest.status).isEqualTo(AnalysisRequestStatus.RUNNING)
        assertThat(secondRequest.status).isEqualTo(AnalysisRequestStatus.RUNNING)
        verifySequence {
            fixture.repository.claimQueued(2)
            fixture.metricService.buildInput(firstRequest.workspaceId, firstRequest.targetId)
            fixture.requestQueueProducer.send(firstRequest.workspaceId, firstRequest.requiredId, firstInput)
            fixture.metricService.buildInput(secondRequest.workspaceId, secondRequest.targetId)
            fixture.requestQueueProducer.send(secondRequest.workspaceId, secondRequest.requiredId, secondInput)
        }
    }

    @Test
    fun `한 요청 처리에 실패해도 남은 요청을 계속 처리한다`() {
        val fixture = DispatcherFixture()
        val failedRequest = createQueuedRequest()
        val successfulRequest = createQueuedRequest()
        val failedInput = createInput(failedRequest)
        val successfulInput = createInput(successfulRequest)
        every { fixture.repository.claimQueued(2) } returns listOf(failedRequest, successfulRequest)
        every { fixture.metricService.buildInput(failedRequest.workspaceId, failedRequest.targetId) } returns failedInput
        every { fixture.metricService.buildInput(successfulRequest.workspaceId, successfulRequest.targetId) } returns successfulInput
        every {
            fixture.requestQueueProducer.send(failedRequest.workspaceId, failedRequest.requiredId, failedInput)
        } throws IllegalStateException("SQS 전송 실패")
        justRun {
            fixture.requestQueueProducer.send(successfulRequest.workspaceId, successfulRequest.requiredId, successfulInput)
        }

        fixture.service.dispatchBatch(batchSize = 2)

        assertThat(failedRequest.status).isEqualTo(AnalysisRequestStatus.QUEUED)
        assertThat(successfulRequest.status).isEqualTo(AnalysisRequestStatus.RUNNING)
        verify(exactly = 1) {
            fixture.requestQueueProducer.send(successfulRequest.workspaceId, successfulRequest.requiredId, successfulInput)
        }
    }

    @Test
    fun `선점할 요청이 없으면 입력을 계산하거나 SQS로 전송하지 않는다`() {
        val fixture = DispatcherFixture()
        every { fixture.repository.claimQueued(10) } returns emptyList()

        fixture.service.dispatchBatch(batchSize = 10)

        verify(exactly = 0) { fixture.metricService.buildInput(any(), any()) }
        verify(exactly = 0) { fixture.requestQueueProducer.send(any(), any(), any()) }
    }
}

private class DispatcherFixture {
    val repository = mockk<AnalysisRequestRepository>()
    val metricService = mockk<SprintAnalysisMetricService>()
    val requestQueueProducer = mockk<RequestQueueProducer>()
    val service = AnalysisDispatcherService(repository, metricService, requestQueueProducer)
}

private val AnalysisRequest.requiredId: UUID
    get() = requireNotNull(id)

private fun createQueuedRequest(): AnalysisRequest =
    AnalysisRequest
        .create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        .apply { id = UUID.randomUUID() }

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
