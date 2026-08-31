package pizza.psycho.sos.analysis.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pizza.psycho.sos.analysis.application.policy.AnalysisDispatchPolicy
import pizza.psycho.sos.analysis.config.AnalysisDispatchProperties
import pizza.psycho.sos.analysis.config.AnalysisRecoveryProperties
import pizza.psycho.sos.analysis.domain.entity.AnalysisRequest
import pizza.psycho.sos.analysis.domain.vo.AnalysisRequestStatus
import pizza.psycho.sos.analysis.infrastructure.persistence.AnalysisRequestRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class AnalysisRecoveryServiceTests {
    private val now = Instant.parse("2026-08-31T01:00:00Z")
    private val repository = mockk<AnalysisRequestRepository>()
    private val recoveryProperties =
        AnalysisRecoveryProperties(
            batchSize = 10,
            staleTimeout = Duration.ofMinutes(10),
        )
    private val service =
        AnalysisRecoveryService(
            analysisRequestRepository = repository,
            analysisDispatchPolicy =
                AnalysisDispatchPolicy(
                    AnalysisDispatchProperties(maxAttempts = 3),
                ),
            properties = recoveryProperties,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

    @Test
    fun `시도가 남은 정체 요청을 즉시 재발행할 수 있도록 QUEUED로 복구한다`() {
        val request = createRunningRequest(attemptCount = 2)
        every {
            repository.claimStaleRunningRequests(
                staleBefore = now.minus(Duration.ofMinutes(10)),
                batchSize = 10,
            )
        } returns listOf(request)

        service.recoverStaleRequests()

        assertThat(request.status).isEqualTo(AnalysisRequestStatus.QUEUED)
        assertThat(request.startedAt).isNull()
        assertThat(request.nextRetryAt).isEqualTo(now)
        assertThat(request.attemptCount).isEqualTo(2)
    }

    @Test
    fun `최대 시도 횟수를 사용한 정체 요청을 FAILED로 종료한다`() {
        val request = createRunningRequest(attemptCount = 3)
        every { repository.claimStaleRunningRequests(any(), any()) } returns listOf(request)

        service.recoverStaleRequests()

        assertThat(request.status).isEqualTo(AnalysisRequestStatus.FAILED)
        assertThat(request.errorMessage)
            .isEqualTo("정체된 분석 요청이 최대 발행 시도 횟수를 사용해 종료되었습니다.")
        assertThat(request.nextRetryAt).isNull()
    }

    @Test
    fun `정체 요청이 없어도 설정된 기준 시각과 batch 크기로 조회한다`() {
        every { repository.claimStaleRunningRequests(any(), any()) } returns emptyList()

        service.recoverStaleRequests()

        verify(exactly = 1) {
            repository.claimStaleRunningRequests(
                staleBefore = now.minus(Duration.ofMinutes(10)),
                batchSize = 10,
            )
        }
    }

    private fun createRunningRequest(attemptCount: Int): AnalysisRequest {
        val request =
            AnalysisRequest
                .create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
                .apply { id = UUID.randomUUID() }

        repeat(attemptCount) { attemptIndex ->
            request.recordDispatchAttempt(now.minusSeconds((attemptCount - attemptIndex).toLong()))
            if (attemptIndex < attemptCount - 1) {
                request.scheduleDispatchRetry(now, "일시적인 발행 실패")
            }
        }
        request.markAsRunning(now)
        return request
    }
}
