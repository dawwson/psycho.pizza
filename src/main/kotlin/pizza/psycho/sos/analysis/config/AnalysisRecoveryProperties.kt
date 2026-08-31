package pizza.psycho.sos.analysis.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "analysis.recovery")
data class AnalysisRecoveryProperties(
    val batchSize: Int = 10,
    val staleTimeout: Duration = Duration.ofMinutes(10),
) {
    init {
        require(batchSize > 0) { "정체된 분석 요청 복구 batch 크기는 1 이상이어야 합니다." }
        require(!staleTimeout.isNegative && !staleTimeout.isZero) {
            "정체된 분석 요청의 판정 시간은 0초보다 길어야 합니다."
        }
    }
}
