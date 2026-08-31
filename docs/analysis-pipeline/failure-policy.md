# 분석 실패 처리 정책

## 목적

이 문서는 Pizza–Pickle 분석 파이프라인에서 오류를 재시도할지, 언제 최종 실패로 종료할지, 정체된 작업과 중복 메시지를 어떻게 처리할지 정의한다.

메시지의 성공·실패 조합은 [메시지 계약](message-contract.md), 상태 전이 결과는 [lifecycle](lifecycle.md)에서 관리한다. at-least-once 전달과 멱등성 결정은 [ADR 0002](../adr/0002-handle-analysis-messages-idempotently.md)에 기록한다.

## 기본 원칙

- 일시적 오류만 자동 재시도한다.
- 재시도는 새로운 요청을 만들지 않고 같은 `analysisRequestId`에서 수행한다.
- 최대 시도 횟수에는 최초 시도를 포함한다.
- 영구 오류와 시도 소진은 `FAILED`로 종료한다.
- 조정 가능한 횟수와 시간은 application configuration으로 관리한다.
- 최종 실패 이후 사용자가 다시 요청하면 새로운 `analysisRequestId`를 생성한다.

## 오류 분류

| 구간 | 재시도 가능 오류 | 영구 오류 |
| --- | --- | --- |
| Pizza 입력 생성 | 현재 자동 재시도하지 않음 | 존재하지 않는 대상, 지원하지 않는 target, 입력 생성 실패 |
| Pizza → request queue | AWS SDK가 retryable로 판정한 timeout, 연결 실패, throttling과 서비스 오류 | 직렬화 오류, 잘못된 설정, retryable이 아닌 SDK 오류 |
| Pickle 요청 검증 | 없음 | 필수 필드 누락, 잘못된 식별자, 지원하지 않는 `schema_version` |
| Pickle → LLM | timeout, 429, 연결 실패, LLM 5xx | 잘못된 요청, 반복되는 빈 응답, 결과 validation 실패 |
| Pickle → response queue | 일시적 연결 또는 SQS 오류 | 계약으로 만들 수 없는 결과 |
| Pizza result consumer | 일시적 DB 또는 transaction 오류 | 잘못된 JSON, 모순된 성공·실패 조합, 알 수 없는 요청 ID |

Pizza 입력 생성 중 발생한 예외는 현재 모두 영구 오류로 처리한다. 일시적인 DB 오류까지 구분해야 한다면 오류 분류 기준과 테스트를 먼저 추가한다.

## Pizza 발행 retry

Pizza request queue 발행 정책은 현재 구현되어 있다.

| 설정 | 기본값 | 의미 |
| --- | --- | --- |
| 최대 발행 시도 | 3회 | 최초 시도 1회와 추가 retry 2회 |
| 최초 retry 대기 | 5초 | 첫 실패 후 다음 발행까지 대기 시간 |
| 다음 retry 대기 | 10초 | 두 번째 실패 후 다음 발행까지 대기 시간 |

발행할 때마다 `attempt_count`와 `last_attempt_at`을 갱신한다. 일시 오류이며 시도가 남으면 실패 이유와 `next_retry_at`을 저장한다. 성공하면 retry 정보와 오류 메시지를 정리하고 `RUNNING`으로 전환한다.

Pizza는 jitter를 적용하지 않는다. 현재 최대 시도가 3회이고 Dispatcher가 처리 수를 제한하며 `FOR UPDATE SKIP LOCKED`로 요청을 분산하기 때문이다. 운영에서 retry 집중이 확인되면 지표를 근거로 재검토한다.

## stale job 복구

<a id="stale-job"></a>`stale job`은 정상 처리 제한 시간을 넘겼지만 종료 상태로 전환되지 않은 요청이다. 애플리케이션 시작이나 상태 이름만으로 stale 여부를 판단하지 않는다.

### `QUEUED`

- `next_retry_at`이 없거나 현재 시각이 지난 요청만 Dispatcher가 선점한다.
- 최대 발행 시도를 사용한 요청은 추가 발행 없이 `FAILED`로 종료한다.

### `RUNNING`

- `started_at`이 현재 시각보다 10분 이상 이전인 요청만 recovery가 선점한다.
- 발행 시도가 남았으면 `QUEUED`로 전환하고 즉시 다시 발행할 수 있게 예약한다.
- 발행 시도를 모두 사용했으면 `FAILED`로 종료한다.
- recovery는 직접 SQS에 발행하지 않는다.

| 설정 | 기본값 |
| --- | --- |
| `RUNNING` stale 기준 | 10분 |
| recovery 실행 주기 | 30초 |
| recovery batch 크기 | 10 |

여러 인스턴스가 같은 요청을 복구하지 않도록 PostgreSQL `FOR UPDATE SKIP LOCKED`를 사용한다. 설정값은 운영 지표를 근거로 조정한다.

## 실패 정보

Pizza 발행 실패는 기존 `analysis_request.error_message`에 명료한 한국어 이유를 저장한다.

- 일시 오류로 retry를 예약하면 마지막 발행 실패 이유를 저장한다.
- 영구 오류나 시도 소진은 최종 실패 이유와 `completed_at`을 저장한다.
- stack trace와 AWS 원문은 DB에 저장하지 않고 로그에 남긴다.
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

## DLQ

처리할 수 없는 메시지는 원본 queue에서 무한 반복하지 않고 DLQ로 격리한다. request와 response 방향은 서로 다른 DLQ를 사용한다.

`visibility timeout`, `redrive policy`, `maxReceiveCount`, 보존 기간과 수동 처리 절차는 아직 확정되지 않았다. 실제 PostgreSQL·SQS 환경의 장애 복구 검증은 [Pizza #23](https://github.com/dawwson/psycho.pizza/issues/23)에서 수행한다.

## 구현 상태

| 범위 | 상태 | 담당 작업 |
| --- | --- | --- |
| Pizza 발행 시도 영속화와 제한 retry | 현재 브랜치에서 구현 | [Pizza #21](https://github.com/dawwson/psycho.pizza/issues/21) |
| Pizza stale `RUNNING` 복구 | 현재 브랜치에서 구현 | [Pizza #21](https://github.com/dawwson/psycho.pizza/issues/21) |
| Pickle 작업 처리·복구 동작 고정 | 완료 | [Pickle #1](https://github.com/dawwson/psycho.pickle/issues/1) |
| Pickle LLM 제한 retry와 최종 실패 통지 | 미구현 | [Pickle #6](https://github.com/dawwson/psycho.pickle/issues/6) |
| Pizza 실패·중복 result 처리 | 미구현 | [Pizza #22](https://github.com/dawwson/psycho.pizza/issues/22) |
| 실제 SQS와 DLQ 장애 복구 검증 | 미구현 | [Pizza #23](https://github.com/dawwson/psycho.pizza/issues/23) |

미구현 정책은 담당 작업과 검증이 완료되기 전까지 현재 동작으로 간주하지 않는다.

## 용어

| 용어 | 의미 |
| --- | --- |
| `stale job` | 정상 처리 제한 시간을 넘겼지만 종료되지 않은 작업 |
| `visibility timeout` | consumer가 처리하는 동안 같은 메시지가 다른 consumer에게 보이지 않는 시간 |
| `redrive policy` | 수신 한도를 넘긴 메시지를 DLQ로 이동하는 규칙 |
| `DLQ` | 자동 처리를 중단한 메시지를 격리하는 dead-letter queue |
