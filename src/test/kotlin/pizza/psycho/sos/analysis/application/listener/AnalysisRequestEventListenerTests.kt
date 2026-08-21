package pizza.psycho.sos.analysis.application.listener

import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pizza.psycho.sos.analysis.application.port.AnalysisJobQueueProducer
import pizza.psycho.sos.analysis.application.port.dto.AnalysisJobQueueItem
import pizza.psycho.sos.analysis.application.service.AnalysisJobRecoveryService
import pizza.psycho.sos.analysis.domain.event.AnalysisRequestCreatedEvent
import java.util.UUID

/**
 * 분석 요청 생성 이벤트와 프로세스 내부 작업 큐 사이의 연결을 고정한다.
 * Spring의 AFTER_COMMIT 실행 자체보다 전달된 요청 ID가 queue item에 보존되는지에 집중한다.
 */
class AnalysisRequestEventListenerTests {
    @Test
    fun `분석 요청 생성 이벤트를 같은 job ID의 queue item으로 등록한다`() {
        // Given: listener 자체는 실제 객체이고, listener 바깥의 queue와 recovery service만 mock이다.
        val jobProducer = mockk<AnalysisJobQueueProducer>()
        val recoveryService = mockk<AnalysisJobRecoveryService>()
        val listener = AnalysisRequestEventListener(jobProducer, recoveryService)
        val analysisRequestId = UUID.randomUUID()
        // enqueue는 반환값이 없으므로 justRun으로 허용하고, 전달 인자는 slot에 capture한다.
        val itemSlot = slot<AnalysisJobQueueItem>()
        justRun { jobProducer.enqueue(capture(itemSlot)) }

        // When: Spring 이벤트 대신 listener 메서드를 직접 호출해 listener 책임만 검증한다.
        listener.handleAnalysisRequestCreated(
            AnalysisRequestCreatedEvent(analysisRequestId = analysisRequestId),
        )

        // Then: 전달 데이터의 상태와 enqueue 호출 횟수를 각각 확인한다.
        assertThat(itemSlot.captured.jobId).isEqualTo(analysisRequestId)
        verify(exactly = 1) { jobProducer.enqueue(itemSlot.captured) }
    }
}
