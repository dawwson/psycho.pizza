package pizza.psycho.sos.analysis.infrastructure.dispatch

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pizza.psycho.sos.analysis.application.service.AnalysisDispatcherService

/**
 * DB에 남아 있는 `QUEUED` 분석 요청을 주기적으로 처리하도록 Dispatcher를 호출한다.
 */
@Component
@ConditionalOnProperty(prefix = "analysis.dispatcher", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class AnalysisDispatcherRunner(
    private val analysisDispatcherService: AnalysisDispatcherService,
) {
    /**
     * 다음 batch 실행과 겹치지 않도록 이전 batch 처리가 끝난 시점부터 설정된 시간만큼 기다린 뒤 다음 batch를 실행한다.
     */
    @Scheduled(fixedDelayString = "\${analysis.dispatcher.fixed-delay-millis:1000}")
    fun dispatch() {
        // 한 번에 선점하는 수를 제한해 하나의 transaction이 과도하게 많은 row lock을 잡지 않게 한다.
        analysisDispatcherService.dispatchBatch(DEFAULT_BATCH_SIZE)
    }

    private companion object {
        const val DEFAULT_BATCH_SIZE = 10
    }
}
