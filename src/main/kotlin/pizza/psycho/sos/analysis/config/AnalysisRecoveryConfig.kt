package pizza.psycho.sos.analysis.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(AnalysisRecoveryProperties::class)
class AnalysisRecoveryConfig
