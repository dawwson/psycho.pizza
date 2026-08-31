package pizza.psycho.sos.analysis.infrastructure.dispatch

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pizza.psycho.sos.analysis.application.service.AnalysisRecoveryService

@Component
@ConditionalOnProperty(prefix = "analysis.recovery", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class AnalysisRecoveryRunner(
    private val analysisRecoveryService: AnalysisRecoveryService,
) {
    @Scheduled(fixedDelayString = "\${analysis.recovery.fixed-delay:30s}")
    fun recoverStaleRequests() {
        analysisRecoveryService.recoverStaleRequests()
    }
}
