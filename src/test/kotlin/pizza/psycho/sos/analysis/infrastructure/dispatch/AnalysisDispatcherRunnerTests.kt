package pizza.psycho.sos.analysis.infrastructure.dispatch

import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import pizza.psycho.sos.analysis.application.service.AnalysisDispatcherService

class AnalysisDispatcherRunnerTests {
    @Test
    fun `스케줄이 실행되면 최대 10개의 분석 요청을 dispatch한다`() {
        val dispatcherService = mockk<AnalysisDispatcherService>()
        val runner = AnalysisDispatcherRunner(dispatcherService)
        justRun { dispatcherService.dispatchBatch(10) }

        runner.dispatch()

        verify(exactly = 1) { dispatcherService.dispatchBatch(10) }
    }
}
