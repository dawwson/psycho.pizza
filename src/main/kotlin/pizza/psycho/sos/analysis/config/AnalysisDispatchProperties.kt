package pizza.psycho.sos.analysis.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "analysis.dispatcher")
data class AnalysisDispatchProperties(
    val maxAttempts: Int = 3,
    val initialRetryDelay: Duration = Duration.ofSeconds(5),
) {
    init {
        require(maxAttempts > 0) { "분석 요청 최대 발행 시도 횟수는 1 이상이어야 합니다." }
        require(!initialRetryDelay.isNegative && !initialRetryDelay.isZero) {
            "분석 요청의 최초 재시도 대기 시간은 0초보다 길어야 합니다."
        }
    }
}
