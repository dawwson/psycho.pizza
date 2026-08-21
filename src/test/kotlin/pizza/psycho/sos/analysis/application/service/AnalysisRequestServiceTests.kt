package pizza.psycho.sos.analysis.application.service

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifySequence
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
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
 * repository와 event publisher만 mock으로 두고 저장 대상은 실제 엔티티를 사용한다.
 */
class AnalysisRequestServiceTests {
    @Nested
    inner class CreateSprintAnalysisRequest {
        @Test
        fun `QUEUED 요청과 초기 리포트를 저장하고 생성 이벤트를 발행한다`() {
            // Given: fixture는 service만 실제 객체로 만들고 repository와 publisher는 mock으로 격리한다.
            val fixture = ServiceFixture()
            val command = createCommand()
            val generatedRequestId = UUID.randomUUID()
            val generatedAt = Instant.parse("2026-08-18T08:00:00Z")
            // slot은 mock 메서드에 실제로 전달된 인자를 나중에 꺼내 검증하기 위한 상자다.
            val requestSlot = slot<AnalysisRequest>()
            val reportSlot = slot<AnalysisReport>()
            val eventSlot = slot<AnalysisRequestCreatedEvent>()

            // every { 호출 } returns 값: mock 호출의 고정 반환값을 정한다.
            // answers는 전달된 인자를 읽거나 변경해야 할 때 사용한다. 여기서는 JPA의 ID/시각 생성을 재현한다.
            every { fixture.analysisRequestRepository.save(capture(requestSlot)) } answers {
                // firstArg는 save에 전달된 첫 번째 인자이며 capture와 같은 실제 엔티티다.
                firstArg<AnalysisRequest>().apply {
                    id = generatedRequestId
                    createdAt = generatedAt
                }
            }
            every { fixture.analysisReportRepository.save(capture(reportSlot)) } answers { firstArg() }
            // 반환값이 없는 Unit 메서드는 justRun으로 "정상 호출된다"고 준비한다.
            justRun { fixture.domainEventPublisher.publish(capture(eventSlot)) }

            // When: 테스트 대상 service를 실제로 실행하는 지점이다.
            val result = fixture.service.createSprintAnalysisRequest(command)

            // Then 1: slot에서 저장 인자를 꺼내 service가 올바른 엔티티를 만들었는지 상태를 검증한다.
            val savedRequest = requestSlot.captured
            assertThat(savedRequest.id).isEqualTo(generatedRequestId)
            assertThat(savedRequest.workspaceId).isEqualTo(command.workspaceId)
            assertThat(savedRequest.targetType).isEqualTo(AnalysisTargetType.SPRINT)
            assertThat(savedRequest.targetId).isEqualTo(command.sprintId)
            assertThat(savedRequest.requestedBy).isEqualTo(command.requesterId)
            assertThat(savedRequest.status).isEqualTo(AnalysisRequestStatus.QUEUED)

            val savedReport = reportSlot.captured
            assertThat(savedReport.analysisRequestId).isEqualTo(generatedRequestId)
            assertThat(savedReport.workspaceId).isEqualTo(command.workspaceId)
            assertThat(savedReport.targetType).isEqualTo(AnalysisTargetType.SPRINT)
            assertThat(savedReport.targetId).isEqualTo(command.sprintId)
            assertThat(savedReport.scoreTotal).isZero()
            assertThat(savedReport.scoreVersion).isEqualTo("v2")
            assertThat(savedReport.categoryPenalties).isEqualTo("[]")
            assertThat(savedReport.penaltyDetails).isEqualTo("[]")
            assertThat(savedReport.aiInsight).isNull()
            assertThat(savedReport.runId).isNull()

            assertThat(result.id).isEqualTo(generatedRequestId)
            assertThat(result.status).isEqualTo(AnalysisRequestStatus.QUEUED.name)
            assertThat(result.createdAt).isEqualTo(generatedAt)
            assertThat(eventSlot.captured.analysisRequestId).isEqualTo(generatedRequestId)

            // Then 2: verifySequence는 아래 호출들이 정확한 순서로 일어났는지 검증한다.
            // 상태 검증만으로는 "이벤트가 저장보다 먼저 발행되는" orchestration 오류를 잡을 수 없다.
            verifySequence {
                fixture.analysisRequestRepository.save(savedRequest)
                fixture.analysisReportRepository.save(savedReport)
                fixture.domainEventPublisher.publish(eventSlot.captured)
            }
        }

        @Test
        fun `저장된 요청에 ID가 없으면 리포트와 생성 이벤트를 만들지 않는다`() {
            val fixture = ServiceFixture()
            // any<T>()는 인자 값과 무관하게 이 stub을 적용한다는 뜻이다.
            every { fixture.analysisRequestRepository.save(any<AnalysisRequest>()) } answers {
                firstArg<AnalysisRequest>().apply { createdAt = Instant.now() }
            }

            val exception =
                catchThrowableOfType(DomainException::class.java) {
                    fixture.service.createSprintAnalysisRequest(createCommand())
                }

            assertThat(exception.errorCode).isEqualTo(AnalysisErrorCode.ANALYSIS_REQUEST_ID_NOT_GENERATED)
            // exactly = 0은 예외 이후 후속 부작용이 발생하지 않았음을 검증한다.
            verify(exactly = 0) { fixture.analysisReportRepository.save(any<AnalysisReport>()) }
            verify(exactly = 0) { fixture.domainEventPublisher.publish(any<AnalysisRequestCreatedEvent>()) }
        }

        @Test
        fun `저장된 요청에 생성 시각이 없으면 리포트와 생성 이벤트를 만들지 않는다`() {
            val fixture = ServiceFixture()
            every { fixture.analysisRequestRepository.save(any<AnalysisRequest>()) } answers {
                firstArg<AnalysisRequest>().apply { id = UUID.randomUUID() }
            }

            val exception =
                catchThrowableOfType(DomainException::class.java) {
                    fixture.service.createSprintAnalysisRequest(createCommand())
                }

            assertThat(exception.errorCode).isEqualTo(AnalysisErrorCode.ANALYSIS_REQUEST_CREATED_AT_NOT_GENERATED)
            verify(exactly = 0) { fixture.analysisReportRepository.save(any<AnalysisReport>()) }
            verify(exactly = 0) { fixture.domainEventPublisher.publish(any<AnalysisRequestCreatedEvent>()) }
        }
    }

    @Nested
    inner class GetAnalysisRequest {
        @Test
        fun `repository가 찾은 요청을 동일한 인스턴스로 반환한다`() {
            val fixture = ServiceFixture()
            val requestId = UUID.randomUUID()
            val savedRequest = createRequest().apply { id = requestId }
            // 실제 DB 대신 repository가 준비한 요청을 반환한다고 가정한다.
            every { fixture.analysisRequestRepository.findById(requestId) } returns Optional.of(savedRequest)

            val result = fixture.service.getAnalysisRequest(requestId)

            // isSameAs는 equals가 아니라 메모리상 동일한 객체인지 확인한다.
            assertThat(result).isSameAs(savedRequest)
        }

        @Test
        fun `repository가 요청을 찾지 못하면 ANALYSIS_REQUEST_NOT_FOUND 예외를 던진다`() {
            val fixture = ServiceFixture()
            val requestId = UUID.randomUUID()
            every { fixture.analysisRequestRepository.findById(requestId) } returns Optional.empty()

            val exception =
                catchThrowableOfType(DomainException::class.java) {
                    fixture.service.getAnalysisRequest(requestId)
                }

            assertThat(exception.errorCode).isEqualTo(AnalysisErrorCode.ANALYSIS_REQUEST_NOT_FOUND)
        }
    }
}

private class ServiceFixture {
    // mockk<T>()로 만든 객체는 실제 repository/publisher 코드를 실행하지 않는다.
    val analysisRequestRepository = mockk<AnalysisRequestRepository>()
    val analysisReportRepository = mockk<AnalysisReportRepository>()
    val domainEventPublisher = mockk<DomainEventPublisher>()

    // 검증 대상인 service만 실제 객체로 만들고 mock 협력 객체를 주입한다.
    val service = AnalysisRequestService(analysisRequestRepository, analysisReportRepository, domainEventPublisher)
}

private fun createCommand(): AnalysisCommand.Create = AnalysisCommand.Create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())

private fun createRequest(): AnalysisRequest = AnalysisRequest.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
