package pizza.psycho.sos.analysis.application.policy

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import pizza.psycho.sos.analysis.config.AnalysisDispatchProperties
import java.time.Duration
import java.time.Instant

class AnalysisDispatchPolicyTests {
    private val policy =
        AnalysisDispatchPolicy(
            AnalysisDispatchProperties(
                maxAttempts = 3,
                initialRetryDelay = Duration.ofSeconds(5),
            ),
        )

    @Test
    fun `최대 시도 횟수보다 적게 시도했으면 재시도할 수 있다`() {
        assertThat(policy.canRetry(attemptCount = 1)).isTrue()
        assertThat(policy.canRetry(attemptCount = 2)).isTrue()
    }

    @Test
    fun `최대 시도 횟수를 사용했으면 재시도할 수 없다`() {
        assertThat(policy.canRetry(attemptCount = 3)).isFalse()
    }

    @Test
    fun `실패 횟수에 따라 다음 재시도 시각을 계산한다`() {
        val failedAt = Instant.parse("2026-08-31T01:00:00Z")

        assertThat(policy.calculateNextRetryAt(attemptCount = 1, failedAt = failedAt))
            .isEqualTo(failedAt.plusSeconds(5))
        assertThat(policy.calculateNextRetryAt(attemptCount = 2, failedAt = failedAt))
            .isEqualTo(failedAt.plusSeconds(10))
    }

    @Test
    fun `다음 시도가 남아 있지 않으면 재시도 시각을 계산할 수 없다`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                policy.calculateNextRetryAt(
                    attemptCount = 3,
                    failedAt = Instant.parse("2026-08-31T01:00:00Z"),
                )
            }.withMessageContaining("다음 시도가 남아 있을 때만")
    }
}
