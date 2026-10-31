# 분석 실패 처리 정책

## 목적

이 문서는 Pizza–Pickle 분석 파이프라인에서 오류를 재시도할지, 언제 최종 실패로 종료할지, 정체된 작업과 중복 메시지를 어떻게 처리할지 정의한다.

메시지의 성공·실패 조합은 [메시지 계약](message-contract.md), 상태 전이 결과는 [lifecycle](lifecycle.md)에서 관리한다. at-least-once 전달과 멱등성 결정은 [ADR 0002](../adr/0002-handle-analysis-messages-idempotently.md)에 기록한다.

## 기본 원칙

- 재시도 대상으로 분류한 오류만 자동 재시도한다.
- 재시도는 새로운 요청을 만들지 않고 같은 `analysisRequestId`에서 수행한다.
- 최대 시도 횟수에는 최초 시도를 포함한다.
- 자동 재시도하지 않는 오류와 시도 소진은 `FAILED`로 종료한다.
- 조정 가능한 횟수와 시간은 애플리케이션 설정으로 관리한다.
- 최종 실패 이후 사용자가 다시 요청하면 새로운 `analysisRequestId`를 생성한다.

## 구간별 오류 처리

아래 표는 전체 파이프라인의 처리 정책이다. 미구현 범위는 [분석 파이프라인 구현 상태](README.md#구현-상태)를 따른다.

| 구간 | 재시도 대상 | 자동 재시도하지 않는 오류 |
| --- | --- | --- |
| Pizza 입력 생성 | 현재 자동 재시도하지 않음 | 존재하지 않는 대상, 지원하지 않는 분석 대상, 입력 생성 실패 |
| Pizza → request queue | 아래 SQS 전송 오류 분류 기준에 해당하는 오류 | 직렬화 오류와 재시도 근거가 없는 오류 |
| Pickle 요청 검증 | 없음 | 필수 필드 누락, 잘못된 식별자, 지원하지 않는 `schema_version` |
| Pickle → LLM | 타임아웃, 요청량 초과, 연결 실패, 서버 오류 | 잘못된 요청, 반복되는 빈 응답, 결과 검증 실패 |
| Pickle → response queue | 일시적 연결 또는 SQS 오류 | 계약으로 만들 수 없는 결과 |
| Pizza result consumer | 일시적 DB 또는 트랜잭션 오류 | 잘못된 JSON, 모순된 성공·실패 조합, 알 수 없는 요청 ID |

Pizza 입력 생성 중 발생한 예외는 현재 원인과 관계없이 `FAILED`로 종료한다.

### SQS 전송 오류 분류

`SqsAnalysisRequestPublisher`는 감싸진 예외에서 `SdkException`을 찾아 다음 순서로 판단한다. SDK의 `retryable()` 값만으로 전체 재시도 가능 여부를 판단하지 않는다.

| 확인 대상 | 재시도 조건 | 이유 |
| --- | --- | --- |
| SDK 표시 | `retryable()` 또는 `RetryUtils.isThrottlingException()`이 true | SDK가 재시도 대상으로 표시한 오류 |
| AWS 응답 | HTTP `500`, `502`, `503`, `504`, `429` 또는 오류 코드 `RequestThrottled` | 서버 오류나 요청량 초과는 일시적일 수 있음 |
| SDK 클라이언트 예외 | 원인에 `ConnectException`, `SocketTimeoutException`, `ApiCallAttemptTimeoutException`이 존재 | 연결 실패나 응답 지연은 다음 시도에서 회복될 수 있음 |

SQS 요청량 초과는 HTTP 400으로도 반환될 수 있으므로 상태 코드와 오류 코드를 함께 확인한다. 그 외 오류는 `Permanent`로 분류해 자동 재시도하지 않는다.

타임아웃은 전송 실패를 확정하지 못하므로 재전송 시 중복 전달될 수 있다. [중복과 충돌](#중복과-충돌) 정책을 따른다.

## Pizza 발행 재시도

| 설정 | 기본값 | 의미 |
| --- | --- | --- |
| 최대 발행 시도 | 3회 | 최초 시도 1회와 추가 재시도 2회 |
| 최초 재시도 대기 | 5초 | 첫 실패 후 다음 발행까지 대기 시간 |
| 다음 재시도 대기 | 10초 | 두 번째 실패 후 다음 발행까지 대기 시간 |

최대 3회는 Dispatcher가 수행하는 애플리케이션 발행 시도 횟수이다. 한 번의 SDK 호출 안에서도 SDK 설정에 따라 HTTP 요청을 재시도할 수 있으므로, 실제 HTTP 전송을 총 3회로 제한한다는 의미는 아니다.

Dispatcher는 발행 가능한 `QUEUED` 요청을 요청별 트랜잭션에서 하나씩 선점한다. 입력 생성과 SQS 발행 전에 `attempt_count`와 `last_attempt_at`을 갱신한다.

- SQS 발행 성공: 재시도 예약과 오류 메시지를 정리하고 `RUNNING`으로 전환한다.
- 재시도 가능한 오류이며 시도가 남음: 실패 이유와 `next_retry_at`을 저장한다.
- 자동 재시도하지 않는 오류 또는 시도 소진: `FAILED`로 종료한다.

재시도 간격에 무작위 지연(jitter)은 적용하지 않는다.

### 검증 범위

| 테스트 | 확인 범위 |
| --- | --- |
| `SqsAnalysisRequestPublisherTests` | 실제 SDK 예외의 오류 분류와 원본 예외 보존 |
| `AnalysisDispatcherSqsIntegrationTests` | 실제 SqsTemplate·SDK, 로컬 HTTP 응답, H2 DB를 연결한 재시도 예약·실패 종료·RUNNING 저장 |

통합 테스트는 SDK 내부 재시도를 끄고 호출 시간을 5초로 제한한다. 이는 테스트 전용 설정이다. 예약 이후의 실제 재전송과 AWS·PostgreSQL·Pickle 전체 연동은 이 테스트의 검증 범위에 포함하지 않는다.

## 정체 요청 복구

<a id="stale-job"></a>정체 요청(`stale job`)은 기준 시간을 넘겨도 종료되지 않은 요청이다.

### `QUEUED`

- `next_retry_at`이 없거나 현재 시각이 지난 요청만 Dispatcher가 선점한다.
- 최대 발행 시도를 사용한 요청은 추가 발행 없이 `FAILED`로 종료한다.

### `RUNNING`

- `started_at`이 현재 시각보다 10분 이상 이전인 요청만 recovery가 선점한다.
- 발행 시도가 남았으면 같은 요청 ID를 유지한 채 `QUEUED`로 전환하고 즉시 다시 발행할 수 있게 예약한다.
- 발행 시도를 모두 사용했으면 `FAILED`로 종료한다.
- recovery는 상태만 변경하고, 실제 재발행은 Dispatcher가 수행한다.

애플리케이션 시작 자체는 복구 조건이 아니다. 기준 시간을 넘기지 않은 `RUNNING`은 변경하지 않는다.

| 설정 | 기본값 |
| --- | --- |
| `RUNNING` stale 기준 | 10분 |
| recovery 실행 주기 | 30초 |
| recovery batch 크기 | 10 |

여러 인스턴스가 같은 요청을 복구하지 않도록 PostgreSQL `FOR UPDATE SKIP LOCKED`를 사용한다. 설정값은 운영 지표를 근거로 조정한다.

## 실패 정보

Pizza 발행 실패는 기존 `analysis_request.error_message`에 실패 이유를 저장한다.

- 일시 오류로 재시도를 예약하면 마지막 발행 실패 이유를 저장한다.
- 자동 재시도하지 않는 오류나 시도 소진은 최종 실패 이유와 `completed_at`을 저장한다.
- 스택 트레이스와 AWS 원문은 DB에 저장하지 않고 로그에 남긴다.
- 발행 실패 전용 `error_code`는 현재 소비처가 없어 추가하지 않는다.

Pickle 최종 실패의 오류 코드와 Pizza 저장 방식은 [Pickle #6](https://github.com/dawwson/psycho.pickle/issues/6)과 [Pizza #22](https://github.com/dawwson/psycho.pizza/issues/22)에서 구현할 목표이다.

## 중복과 충돌

SQS 중복 전달은 정상 상황으로 취급한다.

| 상황 | 확정 정책 | 현재 구현 |
| --- | --- | --- |
| 동일 request 재수신 | Pickle의 기존 Job으로 수렴 | Pickle이 `external_request_id`로 기존 Job 조회 |
| 동일 성공 result 재수신 | 기존 `DONE`과 리포트 유지 | 미구현 |
| 동일 실패 result 재수신 | 기존 `FAILED`와 실패 정보 유지 | 미구현 |
| 종료 상태와 충돌하는 result | 먼저 확정된 종료 상태 유지, 충돌 기록 | 미구현 |

Pizza 결과의 멱등 처리는 [Pizza #22](https://github.com/dawwson/psycho.pizza/issues/22)에서 구현한다.

## DLQ — 미구현 정책

처리할 수 없는 메시지는 원본 queue에서 무한 반복하지 않고 DLQ로 격리한다. request와 response 방향은 서로 다른 DLQ를 사용한다.

`visibility timeout`, `redrive policy`, `maxReceiveCount`, 보존 기간과 수동 처리 절차는 아직 확정되지 않았다. 실제 PostgreSQL·SQS 환경의 장애 복구 검증은 [Pizza #23](https://github.com/dawwson/psycho.pizza/issues/23)에서 수행한다.

## 용어

| 용어 | 의미 |
| --- | --- |
| `stale job` | 정상 처리 제한 시간을 넘겼지만 종료되지 않은 작업 |
| `visibility timeout` | consumer가 처리하는 동안 같은 메시지가 다른 consumer에게 보이지 않는 시간 |
| `redrive policy` | 수신 한도를 넘긴 메시지를 DLQ로 이동하는 규칙 |
| `DLQ` | 자동 처리를 중단한 메시지를 격리하는 dead-letter queue |
