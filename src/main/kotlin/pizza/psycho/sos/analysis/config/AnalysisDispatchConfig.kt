package pizza.psycho.sos.analysis.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

@Configuration
@EnableConfigurationProperties(AnalysisDispatchProperties::class)
class AnalysisDispatchConfig {
    @Bean
    fun analysisClock(): Clock = Clock.systemUTC()

    @Bean
    fun analysisDispatchTransactionOperations(transactionManager: PlatformTransactionManager): TransactionOperations =
        TransactionTemplate(transactionManager)
}
