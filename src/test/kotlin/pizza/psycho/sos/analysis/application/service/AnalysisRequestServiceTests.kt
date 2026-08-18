package pizza.psycho.sos.analysis.application.service

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifySequence
import pizza.psycho.sos.analysis.application.service.dto.AnalysisCommand
import pizza.psycho.sos.analysis.domain.entity.AnalysisReport
import pizza.psycho.sos.analysis.domain.entity.AnalysisRequest
import pizza.psycho.sos.analysis.domain.event.AnalysisRequestCreatedEvent
import pizza.psycho.sos.analysis.domain.exception.AnalysisErrorCode
import pizza.psycho.sos.analysis.domain.vo.AnalysisRequestStatus
import pizza.psycho.sos.analysis.domain.vo.AnalysisTargetType
import pizza.psycho.sos.analysis.infrastructure.persistence.AnalysisReportRepository
import pizza.psycho.sos.analysis.infrastructure.persistence.AnalysisRequestRepository
import pizza.psycho.sos.common.event.DomainEventPublisher
import pizza.psycho.sos.common.handler.DomainException
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * [AnalysisRequestService]가 분석 요청을 생성할 때 만드는 DB 데이터와 도메인 이벤트를 고정한다.
 *
 * repository와 event publisher만 mock으로 두고 저장 대상은 실제 엔티티를 사용한다.
 * 따라서 mock이 엔티티 동작을 대신 흉내 내지 않으며, 서비스가 구성한 요청·리포트 값을 그대로 검증할 수 있다.
 * 실제 JPA mapping과 트랜잭션 커밋 이후 이벤트 실행은 이 테스트의 범위가 아니다.
 */
class AnalysisRequestServiceTests :
    FunSpec({
        context("sprint 분석 요청 생성") {
            test("QUEUED 요청과 초기 리포트를 저장하고 생성 이벤트를 발행한다") {
                val fixture = ServiceFixture()
                val command = createCommand()
                val generatedRequestId = UUID.randomUUID()
                val generatedAt = Instant.parse("2026-08-18T08:00:00Z")
                val requestSlot = slot<AnalysisRequest>()
                val reportSlot = slot<AnalysisReport>()
                val eventSlot = slot<AnalysisRequestCreatedEvent>()

                // 실제 JPA가 save 시 채워주는 식별자와 생성 시각을 repository stub에서 재현한다.
                every { fixture.analysisRequestRepository.save(capture(requestSlot)) } answers {
                    firstArg<AnalysisRequest>().apply {
                        id = generatedRequestId
                        createdAt = generatedAt
                    }
                }
                every { fixture.analysisReportRepository.save(capture(reportSlot)) } answers { firstArg() }
                justRun { fixture.domainEventPublisher.publish(capture(eventSlot)) }

                val result = fixture.service.createSprintAnalysisRequest(command)

                val savedRequest = requestSlot.captured
                savedRequest.id shouldBe generatedRequestId
                savedRequest.workspaceId shouldBe command.workspaceId
                savedRequest.targetType shouldBe AnalysisTargetType.SPRINT
                savedRequest.targetId shouldBe command.sprintId
                savedRequest.requestedBy shouldBe command.requesterId
                savedRequest.status shouldBe AnalysisRequestStatus.QUEUED

                // 초기 리포트는 AI 결과가 도착하기 전에 metric 계산 결과를 담을 빈 그릇으로 함께 생성된다.
                val savedReport = reportSlot.captured
                savedReport.analysisRequestId shouldBe generatedRequestId
                savedReport.workspaceId shouldBe command.workspaceId
                savedReport.targetType shouldBe AnalysisTargetType.SPRINT
                savedReport.targetId shouldBe command.sprintId
                savedReport.scoreTotal shouldBe 0
                savedReport.scoreVersion shouldBe "v2"
                savedReport.categoryPenalties shouldBe "[]"
                savedReport.penaltyDetails shouldBe "[]"
                savedReport.aiInsight shouldBe null
                savedReport.runId shouldBe null

                // 반환값과 후속 queue 작업을 시작하는 이벤트는 저장된 요청 ID를 동일하게 사용해야 한다.
                result.id shouldBe generatedRequestId
                result.status shouldBe AnalysisRequestStatus.QUEUED.name
                result.createdAt shouldBe generatedAt
                eventSlot.captured.analysisRequestId shouldBe generatedRequestId

                // 요청이 저장되어 식별자를 얻은 뒤에만 리포트를 연결하고 생성 이벤트를 발행할 수 있다.
                verifySequence {
                    fixture.analysisRequestRepository.save(savedRequest)
                    fixture.analysisReportRepository.save(savedReport)
                    fixture.domainEventPublisher.publish(eventSlot.captured)
                }
            }

            test("저장된 요청에 ID가 없으면 리포트와 생성 이벤트를 만들지 않는다") {
                val fixture = ServiceFixture()
                val command = createCommand()
                every { fixture.analysisRequestRepository.save(any<AnalysisRequest>()) } answers {
                    firstArg<AnalysisRequest>().apply { createdAt = Instant.now() }
                }

                val exception =
                    shouldThrow<DomainException> {
                        fixture.service.createSprintAnalysisRequest(command)
                    }

                exception.errorCode shouldBe AnalysisErrorCode.ANALYSIS_REQUEST_ID_NOT_GENERATED
                // 연결 키가 없으면 유효한 초기 리포트나 생성 이벤트를 만들 수 없으므로 즉시 중단해야 한다.
                verify(exactly = 0) { fixture.analysisReportRepository.save(any<AnalysisReport>()) }
                verify(exactly = 0) {
                    fixture.domainEventPublisher.publish(any<AnalysisRequestCreatedEvent>())
                }
            }

            test("저장된 요청에 생성 시각이 없으면 리포트와 생성 이벤트를 만들지 않는다") {
                val fixture = ServiceFixture()
                val command = createCommand()
                every { fixture.analysisRequestRepository.save(any<AnalysisRequest>()) } answers {
                    firstArg<AnalysisRequest>().apply { id = UUID.randomUUID() }
                }

                val exception =
                    shouldThrow<DomainException> {
                        fixture.service.createSprintAnalysisRequest(command)
                    }

                exception.errorCode shouldBe AnalysisErrorCode.ANALYSIS_REQUEST_CREATED_AT_NOT_GENERATED
                // API 결과에 필수인 createdAt이 없을 때도 불완전한 후속 데이터를 생성하지 않는다.
                verify(exactly = 0) { fixture.analysisReportRepository.save(any<AnalysisReport>()) }
                verify(exactly = 0) {
                    fixture.domainEventPublisher.publish(any<AnalysisRequestCreatedEvent>())
                }
            }
        }

        context("분석 요청 조회") {
            test("repository가 찾은 요청을 동일한 인스턴스로 반환한다") {
                val fixture = ServiceFixture()
                val requestId = UUID.randomUUID()
                val savedRequest = createRequest().apply { id = requestId }
                every {
                    fixture.analysisRequestRepository.findById(requestId)
                } returns Optional.of(savedRequest)

                val result = fixture.service.getAnalysisRequest(requestId)

                result shouldBeSameInstanceAs savedRequest
            }

            test("repository가 요청을 찾지 못하면 ANALYSIS_REQUEST_NOT_FOUND 예외를 던진다") {
                val fixture = ServiceFixture()
                val requestId = UUID.randomUUID()
                every { fixture.analysisRequestRepository.findById(requestId) } returns Optional.empty()

                val exception =
                    shouldThrow<DomainException> {
                        fixture.service.getAnalysisRequest(requestId)
                    }

                exception.errorCode shouldBe AnalysisErrorCode.ANALYSIS_REQUEST_NOT_FOUND
            }
        }
    })

/**
 * Kotest는 기본적으로 한 spec 인스턴스 안에서 여러 테스트를 실행한다.
 * 각 테스트가 새 fixture를 만들게 해 이전 테스트의 stub 또는 verify 기록이 다음 테스트에 새지 않도록 한다.
 */
private class ServiceFixture {
    val analysisRequestRepository = mockk<AnalysisRequestRepository>()
    val analysisReportRepository = mockk<AnalysisReportRepository>()
    val domainEventPublisher = mockk<DomainEventPublisher>()
    val service =
        AnalysisRequestService(
            analysisRequestRepository = analysisRequestRepository,
            analysisReportRepository = analysisReportRepository,
            domainEventPublisher = domainEventPublisher,
        )
}

private fun createCommand(): AnalysisCommand.Create =
    AnalysisCommand.Create(
        workspaceId = UUID.randomUUID(),
        sprintId = UUID.randomUUID(),
        requesterId = UUID.randomUUID(),
    )

private fun createRequest(): AnalysisRequest =
    AnalysisRequest.create(
        workspaceId = UUID.randomUUID(),
        sprintId = UUID.randomUUID(),
        memberId = UUID.randomUUID(),
    )
