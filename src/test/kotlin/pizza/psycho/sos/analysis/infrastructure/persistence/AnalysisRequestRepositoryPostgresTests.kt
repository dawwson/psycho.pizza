package pizza.psycho.sos.analysis.infrastructure.persistence

import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Testcontainers
import pizza.psycho.sos.analysis.domain.entity.AnalysisRequest
import pizza.psycho.sos.identity.challenge.support.PostgresTestContainerSupport
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Tag("tc")
@Testcontainers
@DataJpaTest
@EnableJpaAuditing
@ActiveProfiles("test-tc")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AnalysisRequestRepositoryPostgresTests : PostgresTestContainerSupport() {
    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var analysisRequestRepository: AnalysisRequestRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `발행 시도 정보를 저장하고 조회한다`() {
        val attemptedAt = Instant.parse("2026-08-30T01:00:00Z")
        val retryAt = Instant.parse("2026-08-30T01:00:05Z")
        val request =
            newAnalysisRequest().apply {
                recordDispatchAttempt(attemptedAt)
                scheduleRetry(retryAt)
            }

        val saved = analysisRequestRepository.saveAndFlush(request)
        entityManager.clear()
        val found = analysisRequestRepository.findById(saved.id!!).orElseThrow()

        assertThat(found.attemptCount).isEqualTo(1)
        assertThat(found.lastAttemptAt).isEqualTo(attemptedAt)
        assertThat(found.nextRetryAt).isEqualTo(retryAt)
    }

    @Test
    fun `QUEUED 요청을 선점하면 batch 크기만큼 반환한다`() {
        val queuedRequests =
            analysisRequestRepository.saveAllAndFlush(
                List(3) { newAnalysisRequest() },
            )
        analysisRequestRepository.saveAndFlush(
            newAnalysisRequest().also { it.markAsRunning() },
        )

        val claimed =
            TransactionTemplate(transactionManager)
                .execute {
                    analysisRequestRepository.claimQueued(batchSize = 2)
                }.orEmpty()

        assertThat(claimed)
            .hasSize(2)
            .allMatch { it in queuedRequests }
    }

    @Test
    fun `두 transaction이 동시에 선점하면 같은 요청을 반환하지 않는다`() {
        /*
         * TX1: 첫 번째 QUEUED row를 선점하고 lock 획득
         *         ↓
         *     commit 하지 않고 lock 유지
         *         ↓
         * TX2: 선점 시도
         *         ↓
         *     TX1이 잡은 row는 SKIP LOCKED
         *         ↓
         *     다른 row 선점
         *         ↓
         * TX1: 이제 commit
         *
         * 두 트랜잭션이 겹쳐서 실행되는 상황을 의도적으로 재현하기 위해 latch 두 개를 쓴다.
         */

        // 분석 요청 2개 생성
        analysisRequestRepository.saveAllAndFlush(List(2) { newAnalysisRequest() })
        // TX1과 TX2를 동시에 실행할 수 있도록 크기 2의 스레드 풀을 생성한다.
        val executor = Executors.newFixedThreadPool(2)
        // TX1의 row 선점이 완료된 후에 TX2를 시작하도록 순서를 제어하는 Latch
        val firstClaimed = CountDownLatch(1)
        // TX2의 선점 조회가 끝나기 전에 TX1이 commit되는 것을 막는 Latch
        val releaseFirstClaim = CountDownLatch(1)
        // 코드 블록을 하나의 트랜잭션으로 실행함
        val transactionTemplate = TransactionTemplate(transactionManager)

        try {
            val firstResult =
                executor.submit<List<UUID>> {
                    // TX1 실행
                    transactionTemplate
                        .execute {
                            val ids = analysisRequestRepository.claimQueued(batchSize = 1).map { it.id!! }

                            // TX1이 row 선점을 완료했음을 테스트 스레드에 알린다.
                            firstClaimed.countDown()

                            // TX2의 선점 조회가 완료될 때까지 TX1을 대기시킨다.
                            // 이 동안 TX1의 transaction이 종료되지 않아 선점한 row lock이 유지된다.
                            check(releaseFirstClaim.await(5, TimeUnit.SECONDS))

                            ids
                        }.orEmpty()
                }

            // TX1의 선점 완료를 최대 5초 기다린 뒤 TX2를 시작한다.
            assertThat(firstClaimed.await(5, TimeUnit.SECONDS)).isTrue()

            val secondResult =
                executor.submit<List<UUID>> {
                    transactionTemplate
                        .execute {
                            analysisRequestRepository.claimQueued(batchSize = 1).map { it.id!! }
                        }.orEmpty()
                }

            val (firstIds, secondIds) =
                assertTimeoutPreemptively(Duration.ofSeconds(10)) {
                    // 첫 번째 행이 잠긴 동안 TX2는 SKIP LOCKED로 다른 행을 가져온다.
                    val secondIds = secondResult.get(5, TimeUnit.SECONDS)

                    // 두 번째 결과를 얻었으므로 TX1의 대기를 끝내고 commit시킨다.
                    releaseFirstClaim.countDown()
                    firstResult.get(5, TimeUnit.SECONDS) to secondIds
                }

            assertThat(firstIds).hasSize(1)
            assertThat(secondIds).hasSize(1)
            assertThat(firstIds).doesNotContainAnyElementsOf(secondIds)
        } finally {
            // 중간에 실패해도 첫 번째 thread가 latch에서 계속 기다리지 않게 한다.
            releaseFirstClaim.countDown()
            executor.shutdownNow()
        }
    }

    private fun newAnalysisRequest(): AnalysisRequest =
        AnalysisRequest.create(
            workspaceId = UUID.randomUUID(),
            sprintId = UUID.randomUUID(),
            memberId = UUID.randomUUID(),
        )
}
