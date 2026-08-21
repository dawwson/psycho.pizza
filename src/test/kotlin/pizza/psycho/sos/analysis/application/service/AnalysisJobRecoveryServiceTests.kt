package pizza.psycho.sos.analysis.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pizza.psycho.sos.analysis.domain.entity.AnalysisRequest
import pizza.psycho.sos.analysis.domain.vo.AnalysisRequestStatus
import pizza.psycho.sos.analysis.infrastructure.persistence.AnalysisRequestRepository
import java.util.UUID

/**
 * [AnalysisJobRecoveryService]가 재시작 시 DB 상태를 기준으로 복구 대상을 만드는 동작을 고정한다.
 *
 * 핵심 정책은 기존 RUNNING 작업을 QUEUED로 되돌린 뒤 모든 QUEUED 작업을 내부 queue 등록 대상으로
 * 반환하는 것이다. 실제 queue enqueue는 AnalysisRequestEventListener의 책임이므로 여기서는 다루지 않는다.
 */
class AnalysisJobRecoveryServiceTests {
    @Test
    fun `RUNNING 작업을 QUEUED로 복구하고 기존 QUEUED 작업과 함께 반환한다`() {
        // Given: 재시작 시 DB에 RUNNING과 QUEUED 작업이 함께 남아 있는 상황을 준비한다.
        val repository = mockk<AnalysisRequestRepository>()
        val service = AnalysisJobRecoveryService(repository)
        val runningRequest = createRecoveryRequest(AnalysisRequestStatus.RUNNING)
        val queuedRequest = createRecoveryRequest(AnalysisRequestStatus.QUEUED)

        // 첫 조회는 복구할 RUNNING 목록, 두 번째 조회는 enqueue할 최종 QUEUED 목록을 의미한다.
        every { repository.findAllByStatus(AnalysisRequestStatus.RUNNING) } returns listOf(runningRequest)
        every { repository.findAllByStatus(AnalysisRequestStatus.QUEUED) } returns
            listOf(queuedRequest, runningRequest)

        // When: 복구 service를 실행한다.
        val recoveredJobs = service.recoverJobs()

        // Then 1: RUNNING 엔티티 자체가 QUEUED로 변경되고 시작 시각이 초기화된다.
        assertThat(runningRequest.status).isEqualTo(AnalysisRequestStatus.QUEUED)
        assertThat(runningRequest.startedAt).isNull()

        // Then 2: 반환 순서는 repository의 QUEUED 조회 순서를 유지하며 각 ID를 queue item으로 변환한다.
        assertThat(recoveredJobs.map { it.jobId })
            .containsExactly(queuedRequest.requiredRecoveryId, runningRequest.requiredRecoveryId)

        verifySequence {
            repository.findAllByStatus(AnalysisRequestStatus.RUNNING)
            repository.findAllByStatus(AnalysisRequestStatus.QUEUED)
        }
        // 종료 상태는 복구 대상 조회조차 하지 않는다.
        verify(exactly = 0) { repository.findAllByStatus(AnalysisRequestStatus.DONE) }
        verify(exactly = 0) { repository.findAllByStatus(AnalysisRequestStatus.FAILED) }
    }

    @Test
    fun `ID가 없는 QUEUED 요청은 queue item으로 만들지 않는다`() {
        val repository = mockk<AnalysisRequestRepository>()
        val service = AnalysisJobRecoveryService(repository)
        val requestWithoutId = AnalysisRequest.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        every { repository.findAllByStatus(AnalysisRequestStatus.RUNNING) } returns emptyList()
        every { repository.findAllByStatus(AnalysisRequestStatus.QUEUED) } returns listOf(requestWithoutId)

        val recoveredJobs = service.recoverJobs()

        // mapNotNull 때문에 DB 식별자가 없는 비정상 fixture는 안전하게 결과에서 제외된다.
        assertThat(recoveredJobs).isEmpty()
    }

    @Test
    fun `복구 대상이 없으면 빈 목록을 반환한다`() {
        val repository = mockk<AnalysisRequestRepository>()
        val service = AnalysisJobRecoveryService(repository)
        every { repository.findAllByStatus(AnalysisRequestStatus.RUNNING) } returns emptyList()
        every { repository.findAllByStatus(AnalysisRequestStatus.QUEUED) } returns emptyList()

        val recoveredJobs = service.recoverJobs()

        assertThat(recoveredJobs).isEmpty()
    }
}

private val AnalysisRequest.requiredRecoveryId: UUID
    get() = requireNotNull(id)

/** setter로 상태를 주입하지 않고 실제 허용 전이를 거쳐 fixture를 만든다. */
private fun createRecoveryRequest(status: AnalysisRequestStatus): AnalysisRequest =
    AnalysisRequest
        .create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        .apply {
            id = UUID.randomUUID()
            if (status == AnalysisRequestStatus.RUNNING) {
                markAsRunning()
            }
        }
