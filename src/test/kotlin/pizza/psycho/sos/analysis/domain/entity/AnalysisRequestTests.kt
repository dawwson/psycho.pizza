package pizza.psycho.sos.analysis.domain.entity

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import pizza.psycho.sos.analysis.domain.exception.AnalysisErrorCode
import pizza.psycho.sos.analysis.domain.vo.AnalysisRequestStatus
import pizza.psycho.sos.common.handler.DomainException
import java.time.Instant
import java.util.UUID
import java.util.stream.Stream

/**
 * [AnalysisRequest]가 현재 제공하는 lifecycle 규칙을 고정하는 characterization test
 *
 * 후속 Dispatcher 및 장애 복구 작업에서 기존 상태 의미가 의도치 않게 달라지는 것을 감지하기 위해
 * 최종 상태뿐 아니라 함께 변경되어야 하는 시간 및 오류 필드까지 하나의 불변식으로 검증한다.
 */
class AnalysisRequestTests {
    @Test
    fun `분석 요청 생성 시 QUEUED 상태로 시작한다`() {
        // Given: 테스트 대상이 사용할 입력값을 준비한다. 도메인 테스트라 mock은 필요하지 않다.
        val workspaceId = UUID.randomUUID()
        val sprintId = UUID.randomUUID()
        val memberId = UUID.randomUUID()

        // When: 실제로 검증할 동작은 한 줄만 실행한다.
        val request = AnalysisRequest.create(workspaceId, sprintId, memberId)

        // Then: AssertJ의 assertThat(actual).검증(expected) 형태로 결과 상태를 확인한다.
        assertThat(request.workspaceId).isEqualTo(workspaceId)
        assertThat(request.targetId).isEqualTo(sprintId)
        assertThat(request.requestedBy).isEqualTo(memberId)
        assertThat(request.status).isEqualTo(AnalysisRequestStatus.QUEUED)
        assertThat(request.startedAt).isNull()
        assertThat(request.completedAt).isNull()
        assertThat(request.errorMessage).isNull()
        assertThat(request.attemptCount).isZero()
        assertThat(request.lastAttemptAt).isNull()
        assertThat(request.nextRetryAt).isNull()
    }

    @Nested
    inner class QueuedState {
        // @Nested는 같은 시작 상태를 공유하는 테스트를 읽기 좋게 묶는다. 별도의 Spring context를 만들지는 않는다.
        @Test
        fun `RUNNING으로 변경하면 시작 시각을 기록한다`() {
            val request = createRequest()
            val before = Instant.now()

            request.markAsRunning()

            // 엔티티가 Instant.now()를 직접 호출하므로 특정 시각 대신 호출 전후 범위로 검증한다.
            assertThat(request.status).isEqualTo(AnalysisRequestStatus.RUNNING)
            assertThat(request.startedAt).isBetween(before, Instant.now())
            assertThat(request.completedAt).isNull()
            assertThat(request.errorMessage).isNull()
            assertThat(request.nextRetryAt).isNull()
        }

        @Test
        fun `발행 시도를 기록하면 횟수와 마지막 시각을 갱신한다`() {
            val request = createRequest()
            val firstAttemptAt = Instant.parse("2026-08-30T01:00:00Z")
            val secondAttemptAt = Instant.parse("2026-08-30T01:01:00Z")

            request.recordDispatchAttempt(firstAttemptAt)
            request.scheduleRetry(secondAttemptAt)
            request.recordDispatchAttempt(secondAttemptAt)

            assertThat(request.attemptCount).isEqualTo(2)
            assertThat(request.lastAttemptAt).isEqualTo(secondAttemptAt)
            assertThat(request.nextRetryAt).isNull()
        }

        @Test
        fun `재시도를 예약하면 다음 발행 가능 시각을 기록한다`() {
            val request = createRequest()
            val retryAt = Instant.parse("2026-08-30T01:00:05Z")

            request.scheduleRetry(retryAt)

            assertThat(request.status).isEqualTo(AnalysisRequestStatus.QUEUED)
            assertThat(request.nextRetryAt).isEqualTo(retryAt)
        }

        @Test
        fun `FAILED로 변경하면 완료 시각과 오류 메시지를 기록한다`() {
            val request = createRequest()
            val retryAt = Instant.parse("2026-08-30T01:00:05Z")
            request.scheduleRetry(retryAt)
            val before = Instant.now()

            request.markAsFailed("분석 입력 생성 실패")

            assertThat(request.status).isEqualTo(AnalysisRequestStatus.FAILED)
            assertThat(request.completedAt).isBetween(before, Instant.now())
            assertThat(request.errorMessage).isEqualTo("분석 입력 생성 실패")
            assertThat(request.nextRetryAt).isNull()
        }
    }

    @Nested
    inner class RunningState {
        @Test
        fun `DONE으로 변경하면 완료 시각을 기록한다`() {
            val request = requestIn(AnalysisRequestStatus.RUNNING)
            val before = Instant.now()

            request.markAsDone()

            assertThat(request.status).isEqualTo(AnalysisRequestStatus.DONE)
            assertThat(request.completedAt).isBetween(before, Instant.now())
            assertThat(request.errorMessage).isNull()
        }

        @Test
        fun `결과와 함께 완료하면 DONE 상태가 된다`() {
            val request = requestIn(AnalysisRequestStatus.RUNNING)
            val before = Instant.now()

            request.complete("분석 결과")

            // result 저장은 AnalysisLifecycleService가 조회한 AnalysisReport의 책임이다.
            assertThat(request.status).isEqualTo(AnalysisRequestStatus.DONE)
            assertThat(request.completedAt).isBetween(before, Instant.now())
            assertThat(request.errorMessage).isNull()
        }

        @Test
        fun `FAILED로 변경하면 완료 시각과 오류 메시지를 기록한다`() {
            val request = requestIn(AnalysisRequestStatus.RUNNING)
            val before = Instant.now()

            request.markAsFailed("LLM 호출 실패")

            assertThat(request.status).isEqualTo(AnalysisRequestStatus.FAILED)
            assertThat(request.completedAt).isBetween(before, Instant.now())
            assertThat(request.errorMessage).isEqualTo("LLM 호출 실패")
        }

        @Test
        fun `재시도 대기 상태로 복구하면 시작 시각을 초기화하고 다음 발행 가능 시각을 기록한다`() {
            val request = requestIn(AnalysisRequestStatus.RUNNING)
            val retryAt = Instant.parse("2026-08-30T01:10:00Z")

            request.markAsQueuedForRetry(retryAt)

            assertThat(request.status).isEqualTo(AnalysisRequestStatus.QUEUED)
            assertThat(request.startedAt).isNull()
            assertThat(request.completedAt).isNull()
            assertThat(request.errorMessage).isNull()
            assertThat(request.nextRetryAt).isEqualTo(retryAt)
        }
    }

    @ParameterizedTest(name = "{0} 상태에서는 {1} 전이를 허용하지 않는다")
    @MethodSource("invalidTransitions")
    fun `허용되지 않은 상태 전이는 요청을 변경하지 않는다`(
        currentStatus: AnalysisRequestStatus,
        transition: Transition,
    ) {
        // Given: @MethodSource가 상태와 실행할 전이를 한 쌍씩 주입한다.
        val request = requestIn(currentStatus)
        // 일부 필드를 바꾼 뒤 실패하는 결함도 잡기 위해 호출 전 snapshot을 보관한다.
        val originalStartedAt = request.startedAt
        val originalCompletedAt = request.completedAt
        val originalErrorMessage = request.errorMessage
        val originalAttemptCount = request.attemptCount
        val originalLastAttemptAt = request.lastAttemptAt
        val originalNextRetryAt = request.nextRetryAt

        // When: catchThrowableOfType은 예상 타입의 예외를 잡아 Then 단계에서 필드까지 검사하게 해준다.
        val exception =
            catchThrowableOfType(DomainException::class.java) {
                transition.apply(request)
            }

        // Then: 예외 종류뿐 아니라 실패한 동작이 엔티티를 부분 변경하지 않았는지도 검증한다.
        assertThat(exception.errorCode).isEqualTo(AnalysisErrorCode.INVALID_ANALYSIS_STATE)
        assertThat(request.status).isEqualTo(currentStatus)
        assertThat(request.startedAt).isEqualTo(originalStartedAt)
        assertThat(request.completedAt).isEqualTo(originalCompletedAt)
        assertThat(request.errorMessage).isEqualTo(originalErrorMessage)
        assertThat(request.attemptCount).isEqualTo(originalAttemptCount)
        assertThat(request.lastAttemptAt).isEqualTo(originalLastAttemptAt)
        assertThat(request.nextRetryAt).isEqualTo(originalNextRetryAt)
    }

    enum class Transition(
        val allowedStatuses: Set<AnalysisRequestStatus>,
        val apply: (AnalysisRequest) -> Unit,
    ) {
        RECORD_DISPATCH_ATTEMPT(setOf(AnalysisRequestStatus.QUEUED), { it.recordDispatchAttempt(Instant.EPOCH) }),
        SCHEDULE_RETRY(setOf(AnalysisRequestStatus.QUEUED), { it.scheduleRetry(Instant.EPOCH) }),
        MARK_RUNNING(setOf(AnalysisRequestStatus.QUEUED), AnalysisRequest::markAsRunning),
        MARK_DONE(setOf(AnalysisRequestStatus.RUNNING), AnalysisRequest::markAsDone),
        COMPLETE(setOf(AnalysisRequestStatus.RUNNING), { it.complete("분석 결과") }),
        MARK_FAILED(
            setOf(AnalysisRequestStatus.QUEUED, AnalysisRequestStatus.RUNNING),
            { it.markAsFailed("분석 실패") },
        ),
        MARK_QUEUED_FOR_RETRY(
            setOf(AnalysisRequestStatus.RUNNING),
            { it.markAsQueuedForRetry(Instant.EPOCH) },
        ),
    }

    companion object {
        /**
         * 전체 상태와 전이의 Cartesian product에서 정상 시작 상태를 제외한다.
         * 반환한 Arguments 하나가 parameterized test 한 번의 실행 케이스가 된다.
         */
        @JvmStatic
        fun invalidTransitions(): Stream<Arguments> =
            AnalysisRequestStatus.entries
                .flatMap { status ->
                    Transition.entries
                        .filter { status !in it.allowedStatuses }
                        .map { transition -> Arguments.of(status, transition) }
                }.stream()

        private fun createRequest(): AnalysisRequest = AnalysisRequest.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())

        /** 공개된 정상 전이만 사용해 원하는 fixture 상태를 만든다. */
        private fun requestIn(status: AnalysisRequestStatus): AnalysisRequest {
            val request = createRequest()
            when (status) {
                AnalysisRequestStatus.QUEUED -> Unit
                AnalysisRequestStatus.RUNNING -> request.markAsRunning()
                AnalysisRequestStatus.DONE -> {
                    request.markAsRunning()
                    request.markAsDone()
                }
                AnalysisRequestStatus.FAILED -> {
                    request.markAsRunning()
                    request.markAsFailed("기존 오류")
                }
            }
            return request
        }
    }
}
