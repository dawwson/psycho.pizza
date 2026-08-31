package pizza.psycho.sos.analysis.infrastructure.dispatch

import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import pizza.psycho.sos.analysis.application.service.AnalysisRecoveryService

class AnalysisRecoveryRunnerTests {
    @Test
    fun `스케줄이 실행되면 정체된 분석 요청을 복구한다`() {
        val recoveryService = mockk<AnalysisRecoveryService>()
        val runner = AnalysisRecoveryRunner(recoveryService)
        justRun { recoveryService.recoverStaleRequests() }

        runner.recoverStaleRequests()

        verify(exactly = 1) { recoveryService.recoverStaleRequests() }
    }
}
