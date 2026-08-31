package pizza.psycho.sos.analysis.application.policy

import org.springframework.stereotype.Component
import pizza.psycho.sos.analysis.config.AnalysisDispatchProperties
import java.time.Instant

// 설정값을 적용해 retry·stale 여부를 판단
@Component
class AnalysisDispatchPolicy(
    private val properties: AnalysisDispatchProperties,
) {
    fun canRetry(attemptCount: Int): Boolean = attemptCount < properties.maxAttempts

    fun calculateNextRetryAt(
        attemptCount: Int,
        failedAt: Instant,
    ): Instant {
        require(attemptCount in 1 until properties.maxAttempts) {
            "재시도 시각은 발행에 실패했고 다음 시도가 남아 있을 때만 계산할 수 있습니다. (시도 횟수=$attemptCount)"
        }

        return failedAt.plus(properties.initialRetryDelay.multipliedBy(attemptCount.toLong()))
    }
}
