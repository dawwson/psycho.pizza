package pizza.psycho.sos.analysis.application.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import io.awspring.cloud.sqs.operations.SqsTemplate
import io.mockk.every
import io.mockk.mockk
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import pizza.psycho.sos.analysis.application.policy.AnalysisDispatchPolicy
import pizza.psycho.sos.analysis.application.service.dto.SprintAnalysisInput
import pizza.psycho.sos.analysis.config.AnalysisDispatchProperties
import pizza.psycho.sos.analysis.domain.entity.AnalysisRequest
import pizza.psycho.sos.analysis.domain.vo.AnalysisRequestStatus
import pizza.psycho.sos.analysis.infrastructure.persistence.AnalysisRequestRepository
import pizza.psycho.sos.analysis.infrastructure.sqs.SqsAnalysisRequestPublisher
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.retry.RetryPolicy
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

// 실제 SqsTemplate·SDK와 H2 DB를 사용하고, AWS 응답만 로컬 서버로 대체한다.
@Tag("integration")
@DataJpaTest
@EnableJpaAuditing
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AnalysisDispatcherSqsIntegrationTests {
    @Autowired
    private lateinit var repository: AnalysisRequestRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var entityManager: EntityManager

    private lateinit var server: HttpServer
    private lateinit var client: SqsAsyncClient
    private val sendCount = AtomicInteger()
    private var responseStatus = 503
    private val now = Instant.parse("2026-08-31T01:00:00Z")

    // 공통 Given: 각 테스트의 로컬 서버·SDK·DB 환경을 준비한다.
    @BeforeEach
    fun setUp() {
        // 이전 테스트의 분석 요청을 삭제한다.
        repository.deleteAll()

        // AWS 대신 요청을 받을 가짜 SQS 서버를 빈 로컬 포트에 만든다.
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // SDK 요청을 읽고, 테스트에서 지정한 성공·오류 응답을 반환한다.
        server.createContext("/") { exchange ->
            val target = exchange.requestHeaders.getFirst("X-Amz-Target").orEmpty()
            val requestBody = exchange.requestBody.use { it.readBytes() }
            val message = if (target.endsWith("SendMessage")) jacksonObjectMapper().readTree(requestBody)["MessageBody"].asText() else ""
            // SDK가 성공 응답을 검증하므로 본문과 메시지 속성의 체크섬도 SQS 형식으로 계산한다.
            val checksum = md5(message.toByteArray())
            val attributes = jacksonObjectMapper().readTree(requestBody)["MessageAttributes"]
            val attributeChecksum = messageAttributesChecksum(attributes)
            val isSend = target.endsWith("SendMessage")
            // 큐 속성 조회는 성공시키고, 메시지 전송에만 테스트별 응답을 적용한다.
            val status = if (isSend) responseStatus else 200
            val body =
                when {
                    !isSend -> """{"Attributes":{}}"""
                    status == 200 ->
                        jacksonObjectMapper().writeValueAsString(
                            mapOf(
                                "MessageId" to UUID.randomUUID().toString(),
                                "MD5OfMessageBody" to checksum,
                                "MD5OfMessageAttributes" to attributeChecksum,
                            ),
                        )
                    status == 400 -> """{"__type":"RequestThrottled","message":"too many requests"}"""
                    status == 403 -> """{"__type":"AccessDeniedException","message":"denied"}"""
                    else -> """{"__type":"ServiceUnavailable","message":"try later"}"""
                }
            // 메시지 전송 요청만 세어 실제 호출 횟수를 검증한다.
            if (isSend) sendCount.incrementAndGet()
            exchange.responseHeaders.add("Content-Type", "application/x-amz-json-1.0")
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        // 서버를 시작한 뒤 실제 SDK 클라이언트를 연결한다.
        server.start()
        client =
            SqsAsyncClient
                .builder()
                .region(Region.AP_NORTHEAST_2)
                // 로컬 서버만 호출하므로 실제 AWS 자격 증명은 사용하지 않는다.
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
                .endpointOverride(URI.create("http://127.0.0.1:${server.address.port}"))
                // SDK 내부 재시도를 끄고 애플리케이션의 발행 결과 처리를 검증한다.
                .overrideConfiguration(
                    ClientOverrideConfiguration
                        .builder()
                        .retryPolicy(RetryPolicy.none())
                        // 가짜 서버가 응답하지 않아도 테스트가 오래 대기하지 않도록 제한한다.
                        .apiCallTimeout(Duration.ofSeconds(5))
                        .build(),
                ).build()
    }

    // 테스트 종료 후 클라이언트와 로컬 서버를 닫는다.
    @AfterEach
    fun tearDown() {
        client.close()
        server.stop(0)
    }

    @Test
    fun `SQS 503 응답은 DB에 다음 재시도를 예약한다`() {
        // Given: 첫 요청에 SQS가 503을 반환한다.
        // When: 요청을 준비하고 Dispatcher를 실행한다.
        val request = dispatch(status = 503)

        // Then: QUEUED를 유지하고 5초 뒤 재시도를 예약한다.
        assertThat(request.status).isEqualTo(AnalysisRequestStatus.QUEUED)
        assertThat(request.attemptCount).isEqualTo(1)
        assertThat(request.nextRetryAt).isEqualTo(now.plusSeconds(5))
    }

    @Test
    fun `SQS 400 요청 제한 응답은 DB에 다음 재시도를 예약한다`() {
        // Given: 상태 코드는 400이지만 오류 코드는 RequestThrottled이다.
        // When: 실제 SDK로 응답을 해석하고 Dispatcher를 실행한다.
        val request = dispatch(status = 400)

        // Then: 일반 요청 오류와 구분해 5초 뒤 재시도를 예약한다.
        assertThat(request.status).isEqualTo(AnalysisRequestStatus.QUEUED)
        assertThat(request.attemptCount).isEqualTo(1)
        assertThat(request.nextRetryAt).isEqualTo(now.plusSeconds(5))
    }

    @Test
    fun `SQS 403 응답은 DB에 실패를 저장한다`() {
        // Given: 첫 요청에 SQS가 403을 반환한다.
        // When: 요청을 준비하고 Dispatcher를 실행한다.
        val request = dispatch(status = 403)

        // Then: 추가 재시도 없이 FAILED로 종료한다.
        assertThat(request.status).isEqualTo(AnalysisRequestStatus.FAILED)
        assertThat(request.attemptCount).isEqualTo(1)
        assertThat(request.nextRetryAt).isNull()
    }

    @Test
    fun `마지막 발행 시도의 SQS 503 응답은 DB에 실패를 저장한다`() {
        // Given: 이미 두 번 시도한 요청에 SQS가 503을 반환한다.
        // When: 요청을 준비하고 Dispatcher를 실행한다.
        val request = dispatch(status = 503, previousAttempts = 2)

        // Then: 세 번째 시도로 횟수를 소진해 FAILED로 종료한다.
        assertThat(request.status).isEqualTo(AnalysisRequestStatus.FAILED)
        assertThat(request.attemptCount).isEqualTo(3)
        assertThat(request.nextRetryAt).isNull()
    }

    @Test
    fun `SQS 성공 응답은 DB에 RUNNING을 저장한다`() {
        // Given: 첫 요청에 SQS가 성공 응답을 반환한다.
        // When: 요청을 준비하고 Dispatcher를 실행한다.
        val request = dispatch(status = 200)

        // Then: RUNNING으로 변경하고 재시도 예약은 남기지 않는다.
        assertThat(request.status).isEqualTo(AnalysisRequestStatus.RUNNING)
        assertThat(request.attemptCount).isEqualTo(1)
        assertThat(request.nextRetryAt).isNull()
    }

    /*
     * 테스트용 분석 요청을 생성하고, 실제 서비스 코드를 실행해 SQS 발행 결과를 DB에 저장하는 함수
     */
    private fun dispatch(
        status: Int,
        previousAttempts: Int = 0,
    ): AnalysisRequest {
        // Given: SQS 응답, 기존 시도 이력과 실행할 서비스를 준비한다.
        responseStatus = status
        val request = AnalysisRequest.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        repeat(previousAttempts) {
            request.recordDispatchAttempt(now.minusSeconds(20L - it))
            request.scheduleDispatchRetry(now.minusSeconds(10), "previous failure")
        }
        repository.saveAndFlush(request)
        // 분석 데이터 생성은 대체하고, 전송·오류 분류·상태 저장은 실제 코드를 실행한다.
        val metricService = mockk<SprintAnalysisMetricService>()
        every { metricService.buildInput(request.workspaceId, request.targetId) } returns createInput(request)
        val publisher =
            SqsAnalysisRequestPublisher(
                SqsTemplate.newTemplate(client),
                "http://127.0.0.1:${server.address.port}/000000000000/analysis-request",
            )
        val service =
            AnalysisDispatcherService(
                repository,
                metricService,
                publisher,
                AnalysisDispatchPolicy(AnalysisDispatchProperties()),
                TransactionTemplate(transactionManager),
                Clock.fixed(now, ZoneOffset.UTC),
            )

        // When: 분석 요청 하나를 전송하고 결과를 DB에 반영한다.
        service.dispatchBatch(1)

        // Then: 전송 1회를 확인하고, 각 테스트에서 상태를 검증하도록 DB에서 다시 읽는다.
        entityManager.clear()

        assertThat(sendCount.get()).isEqualTo(1)
        return repository.findById(requireNotNull(request.id)).orElseThrow()
    }

    // SQS 규칙에 따라 속성 이름순으로 이름·타입·값을 인코딩해 체크섬을 구한다.
    private fun messageAttributesChecksum(attributes: JsonNode?): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            attributes?.properties()?.sortedBy { it.key }?.forEach { (name, value) ->
                for (text in listOf(name, value["DataType"].asText())) {
                    val encoded = text.toByteArray()
                    output.writeInt(encoded.size)
                    output.write(encoded)
                }
                output.writeByte(1)
                val encoded = value["StringValue"].asText().toByteArray()
                output.writeInt(encoded.size)
                output.write(encoded)
            }
        }
        return md5(bytes.toByteArray())
    }

    private fun md5(bytes: ByteArray): String = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun createInput(request: AnalysisRequest): SprintAnalysisInput =
        SprintAnalysisInput(
            schemaVersion = "0.1.0",
            context =
                SprintAnalysisInput.Context(
                    request.workspaceId,
                    SprintAnalysisInput.Context.Sprint(request.targetId, "Sprint 1", 14, 4),
                ),
            summary =
                SprintAnalysisInput.Summary(
                    SprintAnalysisInput.Summary.StatusSnapshot(1, 1, 2, 0),
                ),
            metrics =
                SprintAnalysisInput.Metrics(
                    SprintAnalysisInput.Metrics.Completion(1),
                    SprintAnalysisInput.Metrics.Stability(1, 0),
                    SprintAnalysisInput.Metrics.Flow(1, 0, 1, 0),
                ),
        )
}
