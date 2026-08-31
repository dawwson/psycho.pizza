package pizza.psycho.sos.analysis.application.service

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pizza.psycho.sos.analysis.application.policy.AnalysisDispatchPolicy
import pizza.psycho.sos.analysis.application.port.AnalysisRequestPublishResult
import pizza.psycho.sos.analysis.application.port.AnalysisRequestPublisher
import pizza.psycho.sos.analysis.application.service.dto.SprintAnalysisInput
import pizza.psycho.sos.analysis.config.AnalysisDispatchConfig
import pizza.psycho.sos.analysis.domain.entity.AnalysisRequest
import pizza.psycho.sos.analysis.domain.vo.AnalysisRequestStatus
import pizza.psycho.sos.analysis.infrastructure.persistence.AnalysisRequestRepository
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Tag("integration")
@DataJpaTest
@EnableJpaAuditing
@ActiveProfiles("test")
@Import(AnalysisDispatcherService::class, AnalysisDispatchPolicy::class, AnalysisDispatchConfig::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
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
    private lateinit var analysisRequestPublisher: AnalysisRequestPublisher

    @MockkBean
    private lateinit var clock: Clock

    @Test
    fun `요청별 transaction에서 발행 성공과 재시도 상태를 각각 저장한다`() {
        every { clock.instant() } returns Instant.parse("2026-08-31T01:00:00Z")
        val successfulRequest = saveQueuedRequest()
        val failedRequest = saveQueuedRequest()
        val successfulInput = createInput(successfulRequest)
        val failedInput = createInput(failedRequest)
        every { metricService.buildInput(successfulRequest.workspaceId, successfulRequest.targetId) } returns successfulInput
        every { metricService.buildInput(failedRequest.workspaceId, failedRequest.targetId) } returns failedInput
        every {
            analysisRequestPublisher.publish(successfulRequest.workspaceId, successfulRequest.requiredId, successfulInput)
        } returns AnalysisRequestPublishResult.Published
        val publishException = IllegalStateException("SQS 전송 실패")
        every {
            analysisRequestPublisher.publish(failedRequest.workspaceId, failedRequest.requiredId, failedInput)
        } returns
            AnalysisRequestPublishResult.Failed.Retryable(
                message = "SQS 분석 요청 메시지 전송이 일시적으로 실패했습니다.",
                cause = publishException,
            )

        dispatcherService.dispatchBatch(batchSize = 2)
        entityManager.clear()

        val foundSuccessfulRequest = findRequest(successfulRequest.requiredId)
        val foundFailedRequest = findRequest(failedRequest.requiredId)
        assertThat(foundSuccessfulRequest.status).isEqualTo(AnalysisRequestStatus.RUNNING)
        assertThat(foundSuccessfulRequest.attemptCount).isEqualTo(1)
        assertThat(foundFailedRequest.status).isEqualTo(AnalysisRequestStatus.QUEUED)
        assertThat(foundFailedRequest.attemptCount).isEqualTo(1)
        assertThat(foundFailedRequest.nextRetryAt).isEqualTo(Instant.parse("2026-08-31T01:00:05Z"))
        assertThat(foundFailedRequest.errorMessage).isEqualTo("SQS 분석 요청 메시지 전송이 일시적으로 실패했습니다.")
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
