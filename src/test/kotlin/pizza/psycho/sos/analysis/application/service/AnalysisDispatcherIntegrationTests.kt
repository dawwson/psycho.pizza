package pizza.psycho.sos.analysis.application.service

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.justRun
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import org.springframework.test.context.ActiveProfiles
import pizza.psycho.sos.analysis.application.port.RequestQueueProducer
import pizza.psycho.sos.analysis.application.service.dto.SprintAnalysisInput
import pizza.psycho.sos.analysis.domain.entity.AnalysisRequest
import pizza.psycho.sos.analysis.domain.vo.AnalysisRequestStatus
import pizza.psycho.sos.analysis.infrastructure.persistence.AnalysisRequestRepository
import java.util.UUID

@Tag("integration")
@DataJpaTest
@EnableJpaAuditing
@ActiveProfiles("test")
@Import(AnalysisDispatcherService::class)
class AnalysisDispatcherIntegrationTests {
    @Autowired
    private lateinit var dispatcherService: AnalysisDispatcherService

    @Autowired
    private lateinit var analysisRequestRepository: AnalysisRequestRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    @MockkBean
    private lateinit var metricService: SprintAnalysisMetricService

    @MockkBean
    private lateinit var requestQueueProducer: RequestQueueProducer

    @Test
    fun `SQS 전송에 성공한 요청만 RUNNING으로 저장한다`() {
        val successfulRequest = saveQueuedRequest()
        val failedRequest = saveQueuedRequest()
        val successfulInput = createInput(successfulRequest)
        val failedInput = createInput(failedRequest)
        every { metricService.buildInput(successfulRequest.workspaceId, successfulRequest.targetId) } returns successfulInput
        every { metricService.buildInput(failedRequest.workspaceId, failedRequest.targetId) } returns failedInput
        justRun { requestQueueProducer.send(successfulRequest.workspaceId, successfulRequest.requiredId, successfulInput) }
        every {
            requestQueueProducer.send(failedRequest.workspaceId, failedRequest.requiredId, failedInput)
        } throws IllegalStateException("SQS 전송 실패")

        dispatcherService.dispatchBatch(batchSize = 2)
        entityManager.flush()
        entityManager.clear()

        assertThat(findRequest(successfulRequest.requiredId).status).isEqualTo(AnalysisRequestStatus.RUNNING)
        assertThat(findRequest(failedRequest.requiredId).status).isEqualTo(AnalysisRequestStatus.QUEUED)
    }

    private fun saveQueuedRequest(): AnalysisRequest =
        analysisRequestRepository.saveAndFlush(
            AnalysisRequest.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
        )

    private fun findRequest(id: UUID): AnalysisRequest = analysisRequestRepository.findById(id).orElseThrow()

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
}

private val AnalysisRequest.requiredId: UUID
    get() = requireNotNull(id)
