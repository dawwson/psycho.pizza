package pizza.psycho.sos.analysis.domain.entity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import pizza.psycho.sos.analysis.domain.exception.AnalysisErrorCode
import pizza.psycho.sos.analysis.domain.vo.AnalysisRequestStatus
import pizza.psycho.sos.common.handler.DomainException
import java.time.Instant
import java.util.UUID

/**
 * [AnalysisRequest]가 현재 제공하는 lifecycle 규칙을 고정하는 characterization test다.
 *
 * 이 테스트의 목적은 상태 전이 구현을 개선하는 것이 아니라 후속 Dispatcher 및 장애 복구 작업에서
 * 기존 상태 의미가 의도치 않게 달라지는 것을 감지하는 데 있다. 따라서 각 테스트는 최종 상태만 보지 않고
 * 상태와 함께 변경되어야 하는 시간 및 오류 필드까지 하나의 불변식으로 검증한다.
 *
 * 현재 허용되는 전이는 다음과 같다.
 * - `QUEUED -> RUNNING`: 실제 분석 처리가 시작됨
 * - `RUNNING -> DONE`: 분석 처리가 정상적으로 종료됨
 * - `RUNNING -> FAILED`: 분석 처리가 최종 실패함
 * - `RUNNING -> QUEUED`: 서버 재시작 등의 이유로 작업을 다시 대기열에 넣음
 */
class AnalysisRequestTests :
    FunSpec({
        test("분석 요청 생성 시 QUEUED 상태로 시작한다") {
            val workspaceId = UUID.randomUUID()
            val sprintId = UUID.randomUUID()
            val memberId = UUID.randomUUID()

            val request = AnalysisRequest.create(workspaceId, sprintId, memberId)

            request.workspaceId shouldBe workspaceId
            request.targetId shouldBe sprintId
            request.requestedBy shouldBe memberId
            request.status shouldBe AnalysisRequestStatus.QUEUED
            request.startedAt shouldBe null
            request.completedAt shouldBe null
            request.errorMessage shouldBe null
        }

        context("QUEUED 상태의 요청") {
            test("RUNNING으로 변경하면 시작 시각을 기록한다") {
                val request = createRequest()
                val before = Instant.now()

                request.markAsRunning()

                val after = Instant.now()
                request.status shouldBe AnalysisRequestStatus.RUNNING
                request.startedAt.isBetweenInclusive(before, after) shouldBe true
                request.completedAt shouldBe null
                request.errorMessage shouldBe null
            }
        }

        context("RUNNING 상태의 요청") {
            test("DONE으로 변경하면 완료 시각을 기록한다") {
                val request = requestIn(AnalysisRequestStatus.RUNNING)
                val before = Instant.now()

                request.markAsDone()

                val after = Instant.now()
                request.status shouldBe AnalysisRequestStatus.DONE
                request.completedAt.isBetweenInclusive(before, after) shouldBe true
                request.errorMessage shouldBe null
            }

            test("결과와 함께 완료하면 DONE 상태가 된다") {
                val request = requestIn(AnalysisRequestStatus.RUNNING)
                val before = Instant.now()

                request.complete("분석 결과")

                val after = Instant.now()
                request.status shouldBe AnalysisRequestStatus.DONE
                request.completedAt.isBetweenInclusive(before, after) shouldBe true
                request.errorMessage shouldBe null
            }

            test("FAILED로 변경하면 완료 시각과 오류 메시지를 기록한다") {
                val request = requestIn(AnalysisRequestStatus.RUNNING)
                val before = Instant.now()

                request.markAsFailed("LLM 호출 실패")

                val after = Instant.now()
                request.status shouldBe AnalysisRequestStatus.FAILED
                request.completedAt.isBetweenInclusive(before, after) shouldBe true
                request.errorMessage shouldBe "LLM 호출 실패"
            }

            test("재시도 대기 상태로 복구하면 시작 시각을 초기화한다") {
                val request = requestIn(AnalysisRequestStatus.RUNNING)

                request.markAsQueuedForRetry()

                // 새 worker가 처음부터 다시 선점할 수 있도록 QUEUED로 돌아가고 시작 시각을 비운다.
                request.status shouldBe AnalysisRequestStatus.QUEUED
                request.startedAt shouldBe null
                request.completedAt shouldBe null
                request.errorMessage shouldBe null
            }
        }

        context("허용되지 않은 상태 전이") {
            withData(
                nameFn = { (currentStatus, transition) ->
                    "$currentStatus 상태에서는 $transition 전이를 허용하지 않는다"
                },
                invalidTransitions(),
            ) { (currentStatus, transition) ->
                val request = requestIn(currentStatus)

                // 일부 필드를 바꾼 뒤 실패하는 결함도 잡을 수 있도록 호출 전 snapshot을 보관한다.
                val originalStartedAt = request.startedAt
                val originalCompletedAt = request.completedAt
                val originalErrorMessage = request.errorMessage

                val exception = shouldThrow<DomainException> { transition.apply(request) }

                exception.errorCode shouldBe AnalysisErrorCode.INVALID_ANALYSIS_STATE

                // 실패한 전이는 상태와 부가 필드에 어떤 변화도 남기지 않아야 한다.
                request.status shouldBe currentStatus
                request.startedAt shouldBe originalStartedAt
                request.completedAt shouldBe originalCompletedAt
                request.errorMessage shouldBe originalErrorMessage
            }
        }
    })

/**
 * 테스트할 전이 함수와 그 함수가 요구하는 시작 상태를 한곳에 정의한다.
 *
 * [requiredStatus]는 정상 전이와 중복되는 조합을 제외하는 기준이고,
 * [apply]는 data-driven test가 각 도메인 메서드를 동일한 방식으로 호출하게 해준다.
 */
private enum class Transition(
    val requiredStatus: AnalysisRequestStatus,
    val apply: (AnalysisRequest) -> Unit,
) {
    MARK_RUNNING(AnalysisRequestStatus.QUEUED, AnalysisRequest::markAsRunning),
    MARK_DONE(AnalysisRequestStatus.RUNNING, AnalysisRequest::markAsDone),
    COMPLETE(AnalysisRequestStatus.RUNNING, { it.complete("분석 결과") }),
    MARK_FAILED(AnalysisRequestStatus.RUNNING, { it.markAsFailed("분석 실패") }),
    MARK_QUEUED_FOR_RETRY(AnalysisRequestStatus.RUNNING, AnalysisRequest::markAsQueuedForRetry),
}

/**
 * 전체 상태와 전체 전이의 Cartesian product에서 정상 시작 상태를 제외한다.
 *
 * 예를 들어 MARK_RUNNING은 QUEUED만 허용하므로 RUNNING, DONE, FAILED와의 조합이 반환된다.
 * 상태나 전이가 추가되면 누락된 금지 경로도 자동으로 테스트 목록에 포함된다.
 */
private fun invalidTransitions(): List<Pair<AnalysisRequestStatus, Transition>> =
    AnalysisRequestStatus.entries.flatMap { status ->
        Transition.entries
            .filter { it.requiredStatus != status }
            .map { transition -> status to transition }
    }

// 개별 테스트가 식별자 준비에 신경 쓰지 않고 lifecycle 규칙에만 집중하도록 기본 요청 생성을 모은다.
private fun createRequest(): AnalysisRequest =
    AnalysisRequest.create(
        workspaceId = UUID.randomUUID(),
        sprintId = UUID.randomUUID(),
        memberId = UUID.randomUUID(),
    )

/**
 * setter로 상태를 강제로 주입하지 않고 공개된 정상 전이만 사용해 원하는 fixture 상태를 만든다.
 * 따라서 테스트 준비 과정도 실제 도메인 규칙을 따르며, JPA나 reflection에 의존하지 않는다.
 */
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

private fun Instant?.isBetweenInclusive(
    start: Instant,
    endInclusive: Instant,
): Boolean = this != null && this >= start && this <= endInclusive
