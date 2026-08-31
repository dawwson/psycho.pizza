package pizza.psycho.sos.analysis.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
@EnableConfigurationProperties(AnalysisDispatchProperties::class)
class AnalysisDispatchConfig {
    @Bean
    fun analysisClock(): Clock = Clock.systemUTC()
}
