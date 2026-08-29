# 분석 실패 처리 정책

## 목적

이 문서는 Pizza–Pickle 분석 파이프라인에서 오류를 분류하고 재시도, [`stale job`](#stale-job) 복구, 중복 메시지와 최종 실패를 처리하는 기준을 정의합니다.

PR 1에서는 후속 구현이 따라야 할 정책만 확정했다. Pizza request queue 발행의 시도 횟수와 backoff 세부 값, timeout, Pickle 결과 통지 횟수와 SQS `maxReceiveCount`는 해당 구현과 테스트 PR에서 결정한다.

중복 전달을 정상 상황으로 다루는 결정은 [ADR 0002](../adr/0002-handle-analysis-messages-idempotently.md), 메시지 필드와 성공·실패 조합은 [Pizza–Pickle 메시지 계약](message-contract.md)에 기록합니다.

## 기본 원칙

- 재시도는 같은 `analysisRequestId` 안에서 수행한다.
- 일시적 오류만 자동 재시도하고 영구 오류는 최종 실패로 처리한다.
- Pizza request queue 발행과 Pickle LLM 호출은 최초 시도를 포함해 최대 3회 수행한다.
- 최대 시도 횟수를 모두 사용하면 자동 재시도를 종료한다.
- 최종 실패 이후 사용자가 다시 요청하면 새로운 `analysisRequestId`를 생성한다.
- 현재 구현과 목표 정책을 구분하며, 조정 가능한 횟수와 시간은 application configuration으로 관리한다.

## 오류 분류

### 재시도 가능 오류

같은 입력을 나중에 다시 처리하면 성공할 가능성이 있는 오류입니다.

| 구간 | 예시 | 정책 |
| --- | --- | --- |
| Pizza → request queue | timeout, 일시적 연결 실패, AWS throttling, SQS 5xx | `QUEUED`를 유지하고 backoff 후 재시도 |
| Pickle → LLM | timeout, 429, 일시적 연결 실패, LLM 5xx | 같은 Pickle Job에서 backoff 후 재시도 |
| Pickle → response queue | 일시적 연결 또는 SQS 오류 | 저장된 결과를 유지하고 통지만 재시도 |
| Pizza result consumer | 일시적 DB 또는 transaction 오류 | 메시지를 삭제하지 않고 재처리 허용 |

### 영구 오류

같은 입력으로 다시 처리해도 성공할 가능성이 없는 오류입니다.

| 구간 | 예시 | 정책 |
| --- | --- | --- |
| Pizza 입력 생성 | 존재하지 않는 대상, 지원하지 않는 target, 분석 입력 생성 실패 | 요청을 `FAILED`로 종료 |
| Pickle 요청 검증 | 필수 필드 누락, 잘못된 식별자, 지원하지 않는 `schema_version` | LLM을 호출하지 않고 실패 메시지로 처리 |
| Pickle → LLM | 잘못된 요청, 빈 응답, 결과 validation 실패 | 재시도 가능 여부를 판정하고 반복 실패 시 최종 실패 |
| Pizza 결과 검증 | 잘못된 JSON, 모순된 성공·실패 조합, 알 수 없는 요청 ID | 상태를 변경하지 않고 실패 메시지로 처리 |

### 실패 정보 저장 범위

- Pizza 이슈 #21은 발행 실패 이유를 기존 `error_message`에 저장한다.
- 발행 실패 전용 `error_code`는 현재 API, 지표와 알림에서 사용하는 소비처가 없으므로 추가하지 않는다.
- 발행 실패 전용 `error_code`는 실패 유형별 지표와 알림을 설계하는 observability 작업에서 분류 기준과 함께 추가한다.
- Pickle이 보내는 최종 실패 오류 코드와 예외 매핑은 Pickle PR 8과 Pizza 결과 소비 PR에서 구현과 테스트를 작성하며 확정한다.

## 단계별 최대 시도 횟수

| 단계 | PR 1에서 확정하는 정책 | 세부 결정 시점 |
| --- | --- | --- |
| Pizza request queue 발행 | 최대 3회 | 이슈 #21에서 backoff와 오류별 처리 구현 |
| Pickle LLM 호출 | 최대 3회 | PR 8에서 timeout, 429, 5xx와 validation 처리 구현 |
| Pickle 결과 통지 | 제한된 재시도 필요 | PR 3에서 현재 동작을 테스트로 고정하고 PR 7에서 책임 분리 |
| Pizza response 메시지 처리 | 반복 실패 메시지를 [`DLQ`](#dlq)로 격리 | PR 6에서 삭제·재처리 판정, PR 9에서 실제 SQS 검증 |

- 최대 3회는 최초 시도 1회와 추가 재시도 최대 2회를 뜻한다.
- Pizza는 최초 발행, 발행 실패 후 재시도와 stale `RUNNING` 복구 후 재발행을 모두 하나의 `attempt_count`에 포함한다.
- 별도의 recovery 횟수는 관리하지 않는다.
- 최대 3회는 일시 장애에서 회복할 기회를 주면서 영구 장애를 무한 반복하지 않기 위한 초기 운영값이다. AWS 제한이나 실제 장애 지속 시간을 근거로 산출한 고정값은 아니다.
- Pizza의 최대 시도 횟수는 application configuration으로 관리하고 운영 지표에 따라 조정한다.
- Pickle 결과 통지의 정확한 최대 횟수는 현재 문서에서 확정하지 않는다.

## backoff 원칙

- Pizza request queue 발행과 Pickle LLM 호출의 재시도 사이에 backoff를 적용한다.
- Pizza의 다음 시도 가능 시각은 `next_retry_at`에 저장한다.
- Pizza는 최초 실패 후 5초, 다음 실패 후 10초를 기다리는 backoff로 시작한다.
- Pizza에는 jitter를 적용하지 않는다.
  - 최대 시도가 3회이고 Dispatcher가 batch 제한과 `FOR UPDATE SKIP LOCKED`로 작업을 분산한다.
  - 현재 규모에서는 이 구조로 재시도 집중을 완화할 수 있다고 판단한다.
  - 운영에서 재시도 집중이 확인되면 jitter를 도입한다.
- Pickle LLM 호출의 구체적인 계산식, 간격과 상한은 Pickle PR 8에서 결정한다.
- 구현 PR에서는 설정값과 자동화 테스트를 함께 추가한다.

backoff는 일시 장애가 해소될 시간을 확보하고 장애 중인 외부 시스템에 즉시 요청을 집중하지 않기 위해 사용한다. 영구 오류에는 backoff를 적용하지 않고 즉시 `FAILED`로 종료한다.

## [`stale job`](#stale-job) 복구

상태 이름만으로 [`stale job`](#stale-job)을 판정하지 않는다. 마지막 처리 시각과 정상 처리 제한 시간을 기준으로 오래 멈춘 작업만 복구한다.

### Pizza `QUEUED`

- `next_retry_at`이 지난 요청을 재시도 대상으로 조회한다.
- 최대 3회를 사용한 요청은 `FAILED`로 종료하고 실패 이유를 저장한다.

### Pizza `RUNNING`

- 정상 처리 제한 시간을 넘긴 요청만 복구 대상으로 판정한다.
- recovery scheduler는 정체된 요청을 제한된 batch로 선점한다.
- 여러 인스턴스가 같은 요청을 복구하지 않도록 `FOR UPDATE SKIP LOCKED`를 사용한다.
- 최대 시도 횟수가 남았으면 같은 요청 ID를 유지한 채 `QUEUED`로 전환하고 `next_retry_at`을 갱신한다.
- SQS 재발행은 recovery scheduler가 직접 수행하지 않고 기존 Dispatcher가 담당한다.
- Dispatcher가 복구된 요청을 재발행할 때도 기존 `attempt_count`를 증가시킨다.
- 최대 시도 횟수를 사용한 `RUNNING`이 stale 상태가 되면 추가 발행 없이 `FAILED`로 종료한다.

Pizza는 다음 설정으로 recovery를 시작한다.

| 설정 | 초기값 | 선택 이유 |
| --- | --- | --- |
| `RUNNING` 제한 시간 | 10분 | Pickle의 SQS `visibility timeout` 120초와 recovery lease 300초보다 길게 두어 정상 작업의 성급한 재발행 방지 |
| recovery 실행 주기 | 30초 | 복구 지연과 polling 부하를 함께 고려한 초기 운영값 |
| recovery batch 크기 | 10 | 기존 Dispatcher의 처리 단위와 현재 규모에 맞춘 초기 운영값 |

이 값들은 최적값이나 외부 제한에서 산출한 고정값이 아니다. application configuration으로 관리하고 운영 지표에 따라 조정한다.

Pickle의 기존 복구 동작은 PR 3에서 테스트로 고정한 뒤 PR 7의 책임 분리 과정에서 유지 여부를 판단한다.

## 중복과 충돌 메시지

| 상황 | 정책 |
| --- | --- |
| 동일 request 재수신 | Pickle의 기존 Job으로 수렴하고 완료된 LLM 호출을 반복하지 않음 |
| 동일 성공 result 재수신 | 기존 `DONE`과 리포트를 유지 |
| 동일 실패 result 재수신 | 기존 `FAILED`와 실패 정보를 유지 |
| `DONE` 이후 실패 result 도착 | 기존 `DONE`을 유지하고 충돌을 기록 |
| `FAILED` 이후 성공 result 도착 | 기존 `FAILED`를 유지하고 충돌을 기록 |
| 같은 요청의 서로 다른 성공 result | 최초 반영 결과를 유지하고 충돌을 기록 |

Pizza가 메시지를 삭제할지 재처리할지 판단하는 구체적인 코드 경로는 PR 6에서 구현하고 테스트합니다.

## [`DLQ`](#dlq) 정책

- 처리할 수 없는 메시지를 원본 queue에서 무한 반복하지 않고 [`DLQ`](#dlq)로 격리합니다.
- request와 response 방향의 실패를 구분할 수 있도록 각각의 [`DLQ`](#dlq)를 사용합니다.
- [`visibility timeout`](#visibility-timeout), [`redrive policy`](#redrive-policy), `maxReceiveCount`와 보존 기간은 PR 9에서 실제 SQS 환경을 구성하며 결정합니다.
- PR 9에서 중복 전달, 처리 프로세스 종료와 최대 수신 횟수 초과 후 [`DLQ`](#dlq) 이동을 검증합니다.

PR 1에서는 [`DLQ`](#dlq) 격리 원칙만 정의하며 모니터링, 수동 재처리와 폐기 절차는 확정하지 않습니다.

## 최종 실패

최종 실패는 다음 상황에 확정합니다.

- 영구 오류로 판정한 경우
- Pizza request queue 발행 최대 3회를 사용한 경우
- Pickle LLM 호출 최대 3회를 사용한 경우
- 반복되는 빈 응답 또는 결과 validation 실패가 최종 실패 기준에 도달한 경우
- 최대 발행 시도 횟수를 사용한 `RUNNING` 요청이 [`stale job`](#stale-job)으로 판정된 경우

Pizza 발행 실패와 Pickle 최종 실패에서 저장하는 정보는 [실패 정보 저장 범위](#실패-정보-저장-범위)를 따른다.

## 현재 구현과 목표 정책의 차이

| 항목 | 현재 구현 | 목표 정책과 담당 PR |
| --- | --- | --- |
| Pizza request 발행 | 입력 계산 또는 SQS 오류 시 retry 정보 없이 `QUEUED` 유지 | 최대 3회 제한 재시도 — 이슈 #21 |
| Pizza retry 정보 | 시도 횟수와 다음 시각 없음 | `attempt_count`, `last_attempt_at`, `next_retry_at` 저장 — 이슈 #21 |
| Pizza 재시작 복구 | `QUEUED`는 다시 발견하지만 `RUNNING` 복구 없음 | [`stale job`](#stale-job)만 복구 — 이슈 #21 |
| Pickle 중복 request | `external_request_id`로 기존 Job 조회 | 기존 동작을 테스트로 고정 — PR 3 |
| Pickle LLM 오류 | 제한 재시도 정책이 완결되지 않음 | timeout·429·5xx 분류와 최대 3회 — PR 8 |
| Pickle 결과 통지 | 재시도 상태와 시도 횟수를 저장 | 기존 동작 고정 및 책임 분리 — PR 3·7 |
| Pizza 실패 result | nullable `result`를 역직렬화하지 못함 | 최종 실패를 `FAILED`로 반영 — PR 6 |
| Pizza 중복 result | 두 번째 종료 전이에서 예외 가능 | 동일 결과를 멱등하게 처리 — PR 6 |
| [`DLQ`](#dlq) | 코드에서 실제 queue 설정을 확인할 수 없음 | 실제 PostgreSQL·SQS 장애 복구 검증 — PR 9 |

후속 구현이 완료되기 전에는 목표 정책을 현재 동작으로 간주하지 않습니다.

## 용어

| 영어 원문 | 의미 |
| --- | --- |
| <a id="stale-job"></a>`stale job` | 정상 처리 제한 시간을 초과했지만 종료 상태로 전환되지 않은 작업 |
| <a id="visibility-timeout"></a>`visibility timeout` | consumer가 메시지를 처리하는 동안 같은 메시지가 다른 consumer에게 보이지 않도록 숨기는 시간 |
| <a id="redrive-policy"></a>`redrive policy` | 처리되지 않은 메시지를 다시 전달할 최대 횟수와 한도 초과 시 이동할 DLQ를 지정하는 SQS 정책 |
| <a id="dlq"></a>`DLQ` | 자동 처리를 중단한 메시지를 격리하는 dead-letter queue |
