# 테스트 작성 원칙

## 목적과 범위

이 문서는 Pizza 저장소에서 새 테스트를 작성할 때 적용할 테스트 계층, 기술 스택, fixture, mock, assertion과 주석 작성 기준을 정의합니다.

기존 테스트 전체를 즉시 같은 형태로 변환하는 규칙은 아닙니다. 새 테스트와 동작 변경으로 함께 수정하는 테스트부터 점진적으로 적용하며, 요청받은 변경과 무관한 테스트 스타일 정리는 별도 작업으로 분리합니다.

분석 요청 lifecycle의 상태별 정책, 중복 메시지, 재시도와 장애 시나리오처럼 기능에 종속된 검증 범위는 `docs/analysis-pipeline/testing.md`에서 관리합니다. 이 문서는 그러한 시나리오를 코드로 표현하는 공통 방법에 집중합니다.

## 기본 기술 스택

| 역할 | 기술 | 사용 목적 |
| --- | --- | --- |
| 테스트 실행 및 구조 | JUnit 5 | `@Test`, `@Nested`, parameterized test와 tag |
| Kotlin mocking | MockK | repository, 외부 port와 협력 service의 stub 및 호출 검증 |
| Assertion | AssertJ | 상태, 예외, 컬렉션과 시간 범위 검증 |
| Spring 통합 테스트 | Spring Boot Test | bean wiring, transaction과 Spring event 연동 검증 |
| PostgreSQL 통합 테스트 | Testcontainers PostgreSQL | 실제 query, JPA mapping과 migration 검증 |

새 분석 테스트는 JUnit 5, MockK와 AssertJ 조합을 기본으로 합니다. 기존 테스트에는 Mockito, JUnit Assertions와 Kotlin Test assertion도 존재하지만, 관련 없는 파일을 일괄 변환하지 않습니다. 한 테스트 파일 안에서는 mocking 및 assertion 스타일을 혼용하지 않습니다.

## 테스트 계층 선택

가장 작은 범위에서 동작을 충분히 검증할 수 있는 테스트를 선택합니다.

| 계층 | 실제로 사용하는 대상 | 대체하는 대상 | 주요 검증 |
| --- | --- | --- | --- |
| 도메인 단위 테스트 | entity, value object, domain service | 없음 | 상태 전이, 계산과 불변식 |
| application service 단위 테스트 | 검증 대상 service와 실제 domain entity | repository, 외부 port, 협력 service | 반환값, 엔티티 상태와 필수 상호작용 |
| Spring 통합 테스트 | Spring context와 실제 bean | 필요한 외부 시스템만 대체 | transaction, event phase, 설정과 wiring |
| PostgreSQL 통합 테스트 | PostgreSQL, JPA repository와 mapping | 실제 외부 API | query, constraint, JSONB 및 migration |

도메인 객체만으로 검증할 수 있는 동작에 mock이나 Spring context를 추가하지 않습니다. 반대로 transaction rollback이나 `@TransactionalEventListener(AFTER_COMMIT)`처럼 framework 경계가 핵심인 동작은 순수 단위 테스트만으로 보장한다고 표현하지 않습니다.

## Given–When–Then

테스트는 준비, 실행, 검증의 흐름이 드러나게 작성합니다.

```kotlin
@Test
fun `QUEUED 요청을 조회해 RUNNING으로 변경한다`() {
    // Given
    val request = createRequest()
    every { repository.findById(request.id) } returns Optional.of(request)

    // When
    service.markRunning(request.id)

    // Then
    assertThat(request.status).isEqualTo(AnalysisRequestStatus.RUNNING)
}
```

- **Given**: 입력, fixture와 mock 응답을 준비합니다.
- **When**: 검증 대상의 동작을 가급적 한 번 실행합니다.
- **Then**: 결과 상태를 우선 확인하고 필요한 외부 상호작용을 추가로 검증합니다.
- 각 단계가 빈 줄만으로 명확하다면 `Given`, `When`, `Then` 주석을 반복하지 않아도 됩니다.
- 하나의 테스트가 여러 독립 동작을 실행한다면 테스트 분리를 먼저 검토합니다.

## 상태 검증과 상호작용 검증

상태 검증은 실행 결과로 객체와 반환값이 어떻게 달라졌는지 확인합니다.

```kotlin
assertThat(request.status).isEqualTo(AnalysisRequestStatus.DONE)
assertThat(report.aiInsight).isEqualTo("분석 결과")
```

상호작용 검증은 저장, 이벤트 발행과 외부 메시지 전송처럼 결과 상태만으로 관찰할 수 없는 호출을 확인합니다.

```kotlin
verify(exactly = 1) {
    requestQueueProducer.send(workspaceId, jobId, input)
}
```

다음 기준을 적용합니다.

- 결과를 상태로 확인할 수 있으면 상태 검증을 우선합니다.
- repository 저장, 이벤트 발행, 외부 port 호출은 필요할 때 상호작용을 검증합니다.
- 내부 구현의 모든 호출을 고정하지 않습니다.
- 호출 순서가 데이터 일관성이나 lifecycle 의미에 영향을 줄 때만 `verifySequence`를 사용합니다.
- 실패 이후 부작용이 없어야 하는 경우 `verify(exactly = 0)`으로 미호출을 검증합니다.

## MockK 사용 기준

검증 대상은 실제 객체로 만들고, 테스트 범위를 벗어난 협력 객체만 mock으로 대체합니다.

| 문법 | 의미 |
| --- | --- |
| `mockk<T>()` | 실제 구현을 실행하지 않는 mock 생성 |
| `every { call } returns value` | 반환값이 있는 호출의 고정 응답 준비 |
| `answers { ... }` | 호출 인자를 사용하거나 변경하는 동적 응답 준비 |
| `justRun { call }` | `Unit` 반환 메서드의 정상 실행 준비 |
| `throws exception` | 특정 단계의 장애 재현 |
| `slot<T>()`, `capture(slot)` | 실제 전달된 인자 보관 |
| `verify` | 호출 여부와 횟수 검증 |
| `verifySequence` | 의미 있는 호출 순서 검증 |

```kotlin
val requestSlot = slot<AnalysisRequest>()

every { requestRepository.save(capture(requestSlot)) } answers {
    firstArg<AnalysisRequest>().apply {
        id = generatedRequestId
        createdAt = generatedAt
    }
}
```

위 예제에서 `slot`은 `save`에 실제로 전달된 엔티티를 보관하고, `answers`는 JPA가 저장 시 생성하는 ID와 시각을 단위 테스트에서 재현합니다.

다음 사용은 피합니다.

- 도메인 entity 자체를 mock으로 만들어 상태 전이를 흉내 내는 방식
- 필요한 호출을 알 수 없게 만드는 지나치게 넓은 relaxed mock
- 구현 세부사항을 모두 고정하는 과도한 `verify`
- 같은 테스트 파일에서 MockK와 Mockito를 혼용하는 방식

## Fixture 작성 원칙

- 검증 대상 service는 실제 객체로 만들고 mock 협력 객체를 주입합니다.
- entity와 value object는 가능한 한 실제 객체를 사용합니다.
- 상태 setter나 reflection으로 상태를 강제하기보다 공개된 정상 전이를 통해 fixture를 만듭니다.
- 각 테스트는 독립된 fixture와 mock 호출 기록을 사용합니다.
- 반복되는 생성 코드가 테스트의 핵심을 가리면 의미가 드러나는 helper 또는 fixture로 추출합니다.
- helper가 숨기는 전제조건이 중요하면 함수명이나 짧은 주석으로 밝힙니다.

## 테스트 구조와 이름

### `@Nested`

같은 시작 상태나 같은 service 메서드의 시나리오가 여러 개일 때 관련 테스트를 묶습니다.

```kotlin
@Nested
inner class RunningState {
    @Test
    fun `DONE으로 변경하면 완료 시각을 기록한다`() { /* ... */ }

    @Test
    fun `FAILED로 변경하면 오류 메시지를 기록한다`() { /* ... */ }
}
```

`inner class`는 테스트 그룹이고 `@Test`가 붙은 `fun`이 실제 실행 사례입니다. 테스트가 적거나 이름만으로 조건이 충분히 명확하면 평평한 구조를 유지합니다.

### Parameterized test

동일한 규칙을 여러 입력에 반복 검증할 때 `@ParameterizedTest`를 사용합니다.

- enum 전체가 입력이면 `@EnumSource`를 우선합니다.
- 둘 이상의 값이나 계산된 조합이면 `@MethodSource`를 사용합니다.
- 실패 시 어떤 입력이 문제인지 알 수 있도록 표시 이름을 지정합니다.

### 테스트 이름

테스트 이름은 `조건 + 실행 + 기대 결과`가 드러나게 작성합니다.

```kotlin
fun `RUNNING 요청을 FAILED로 변경하면 오류 메시지를 기록한다`
fun `연결된 리포트가 없으면 ANALYSIS_REPORT_NOT_FOUND 예외를 던진다`
```

`정상 동작한다`, `테스트한다`처럼 기대 결과가 불명확한 이름은 피합니다.

## 주석 작성 원칙

주석은 코드 문법을 반복하지 않고 테스트 선택의 이유, 경계와 놓치기 쉬운 제약을 설명합니다.

주석이 유용한 경우:

- 낯선 mock 문법이 어떤 테스트 역할을 수행하는지 설명할 때
- 호출 순서가 비즈니스 의미를 갖는 이유를 밝힐 때
- `exactly = 0`으로 방지하려는 부작용을 설명할 때
- 실제 infrastructure와 단위 테스트의 차이를 명시할 때
- transaction rollback처럼 현재 테스트가 검증하지 못하는 경계를 밝힐 때
- characterization test가 현재 동작을 고정하는 이유를 기록할 때

코드를 그대로 번역하는 주석은 피합니다.

```kotlin
// 피한다: status가 DONE인지 확인한다.
assertThat(request.status).isEqualTo(AnalysisRequestStatus.DONE)

// 권장: 단위 테스트는 Spring transaction rollback을 실행하지 않으므로
// 예외 이후 DB 상태의 rollback은 별도 통합 테스트에서 검증한다.
```

학습을 위한 설명이 더 이상 필요하지 않을 정도로 팀에 관례가 정착되면 자명한 문법 설명은 줄이고 결정 이유와 테스트 경계에 관한 주석은 유지합니다.

## Characterization test

리팩터링 전에 현재 동작을 보호하는 characterization test에는 다음 기준을 적용합니다.

- 현재 구현이 실제로 수행하는 동작을 먼저 고정합니다.
- 후속 설계의 기대 동작을 현재 테스트에 섞지 않습니다.
- 발견한 결함을 테스트에서 몰래 정상화하지 않고 후속 변경 대상으로 기록합니다.
- framework 동작을 unit mock으로 완전히 재현했다고 표현하지 않습니다.
- 단위 테스트가 검증하지 못한 transaction, persistence와 메시징 경계를 명시합니다.
- 결함을 수정할 때는 바뀐 기대 동작과 회귀 테스트를 같은 변경에 포함합니다.

## 검증 명령

변경 범위에 맞는 최소 검증을 실행합니다.

| 변경 범위 | 명령 |
| --- | --- |
| Kotlin 코드와 기본 단위 테스트 | `./gradlew ktlintCheck`, `./gradlew test` |
| `integration` 태그 테스트 | `./gradlew integrationTest` |
| PostgreSQL query, mapping 또는 migration | `./gradlew testTc` |

테스트를 단독 실행해 빠르게 피드백을 받은 뒤 최종적으로 변경 범위에 필요한 전체 task를 실행합니다. 테스트를 통과시키기 위해 의미 있는 assertion을 삭제하거나 협력 객체 전체를 느슨한 mock으로 대체하지 않습니다.
