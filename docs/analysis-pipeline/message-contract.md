# Pizza–Pickle 메시지 계약

## 목적

이 문서는 Pizza와 Pickle이 SQS로 교환하는 분석 요청과 결과 JSON의 필드, 의미와 호환성 규칙을 정의한다. 언어별 DTO 이름이 아니라 실제 JSON body를 계약 기준으로 사용한다.

Box–Pizza REST API는 이 내부 계약과 분리한다. 내부 메시지를 변경해도 기존 REST 상태 이름과 응답 필드를 함께 변경하지 않는다.

## 책임 경계

| 구성요소 | 책임 |
| --- | --- |
| Pizza | `analysisRequestId` 발급, 분석 입력 생성과 request message 발행 |
| Pickle | `external_request_id`별 작업 관리, LLM 호출과 최종 결과 통지 |
| Pizza result consumer | 결과 검증, lifecycle 전이와 리포트 반영 |

SQS는 메시지를 중복하거나 지연해 전달할 수 있다. Pizza와 Pickle은 `external_request_id`와 각 시스템의 영속 상태를 처리 기준으로 사용한다.

## 공통 규칙

- JSON 필드명은 `snake_case`를 사용한다.
- UUID는 하이픈을 포함한 표준 문자열로 직렬화한다.
- 시각은 timezone을 포함한 ISO 8601 문자열로 직렬화한다.
- `external_request_id`는 Pizza의 `analysisRequestId`와 같은 값이다.
- 같은 요청을 재발행할 때 `external_request_id`를 바꾸지 않는다.
- Pickle의 `job_id`는 내부 추적 ID이며 Pizza lifecycle 식별자로 사용하지 않는다.
- 필수 필드는 누락과 JSON `null`을 허용하지 않는다.
- nullable 필드는 JSON `null`을 허용한다.

## 현재 request message

Pizza가 request queue에 발행하는 현재 구조이다.

| 필드 | 타입 | 필수 | 현재 의미 |
| --- | --- | --- | --- |
| `external_request_id` | UUID string | 예 | Pizza의 `analysisRequestId`, 중복 판정 기준 |
| `result_fetch_url` | string | 예 | Pickle schema 호환용 placeholder URL |
| `openai_request` | object | 예 | Pizza가 계산한 분석 입력 |
| `tenant` | string | 아니요 | 현재 `null` |
| `context` | object | 아니요 | 현재 `null` |

`openai_request`의 최상위 구조는 다음과 같다.

| 필드 | 타입 | 필수 | 의미 |
| --- | --- | --- | --- |
| `schema_version` | string | 예 | 현재 값 `0.1.0` |
| `context` | object | 예 | workspace와 sprint 식별 정보 |
| `summary` | object | 예 | 분석 시점의 task 상태 요약 |
| `metrics` | object | 예 | 완료, 안정성, 흐름 지표 |

```json
{
  "external_request_id": "de305d54-75b4-431b-adb2-eb6b9e546014",
  "result_fetch_url": "https://your-domain/api/v1/analysis/requests/de305d54-75b4-431b-adb2-eb6b9e546014/result",
  "openai_request": {
    "schema_version": "0.1.0",
    "context": {
      "workspace_id": "8a1d8f43-8cb5-45f2-8ab1-53c0b0c68277",
      "sprint": {
        "id": "4f61e9c8-658d-457f-ad09-f5d2216a0f18",
        "name": "Sprint 12",
        "period_days": 14,
        "total_tasks_count": 12
      }
    },
    "summary": {
      "status_snapshot": {
        "todo_count": 2,
        "in_progress_count": 3,
        "done_count": 6,
        "canceled_count": 1
      }
    },
    "metrics": {
      "completion": {
        "unassigned_tasks_count": 1
      },
      "stability": {
        "sprint_goal_change_count": 1,
        "sprint_period_change_count": 0
      },
      "flow": {
        "rework_events_count": 2,
        "todo_to_done_direct_count": 1,
        "scope_churn_events_count": 3,
        "canceled_tasks_count": 1
      }
    }
  },
  "tenant": null,
  "context": null
}
```

`result_fetch_url`은 Pickle의 현재 request schema가 요구하지만 실제 분석 실행에는 사용하지 않는다. Pizza도 유효한 endpoint가 아닌 placeholder를 생성하므로 조회 계약으로 간주하지 않는다. 제거하려면 Pizza와 Pickle 양쪽 DTO와 테스트를 함께 변경해야 한다.

Pickle은 같은 `external_request_id`를 다시 받으면 기존 Job을 조회한다. 완료된 Job의 LLM 호출을 반복하지 않는 규칙은 [ADR 0002](../adr/0002-handle-analysis-messages-idempotently.md)를 따른다.

## 현재 result message 소비

Pizza가 현재 역직렬화할 수 있는 결과 구조는 성공 결과에 한정된다.

| 필드 | 타입 | 현재 제약 |
| --- | --- | --- |
| `job_id` | integer | 필수 |
| `external_request_id` | string | UUID 문자열이어야 함 |
| `openai_response_id` | string 또는 null | 역직렬화하지만 lifecycle 판정에 사용하지 않음 |
| `openai_state` | string | 역직렬화하지만 성공 여부를 검증하지 않음 |
| `postprocess_state` | string | 역직렬화하지만 성공 여부를 검증하지 않음 |
| `result.analysis` | string | non-null 필수 |
| `error` | object 또는 null | 역직렬화하지만 처리하지 않음 |
| `occurred_at` | ISO 8601 string | 필수 |

```json
{
  "job_id": 42,
  "external_request_id": "de305d54-75b4-431b-adb2-eb6b9e546014",
  "openai_response_id": "langchain-42-2026-08-17T10:00:00Z",
  "openai_state": "completed",
  "postprocess_state": "notify_in_progress",
  "result": {
    "analysis": "스프린트 목표 변경과 범위 증가가 함께 발생했습니다."
  },
  "error": null,
  "occurred_at": "2026-08-17T10:00:00Z"
}
```

현재 consumer는 `external_request_id`로 요청을 조회하고 `result.analysis`를 리포트에 저장한 뒤 `DONE`으로 전환한다. `openai_state`, `postprocess_state`와 `error` 조합을 검증하지 않으며, 전체 message body를 실패 로그에 남긴다. 이 동작은 최종 실패와 민감정보 처리에 안전하지 않다.

## 목표 result contract

다음 구조는 [Pizza #22](https://github.com/dawwson/psycho.pizza/issues/22), [Pickle #6](https://github.com/dawwson/psycho.pickle/issues/6)과 [Pizza #18](https://github.com/dawwson/psycho.pizza/issues/18)에서 구현하고 양쪽 테스트로 검증할 목표이다.

성공 결과는 다음 조건을 모두 만족해야 한다.

- `openai_state`가 `completed`이다.
- `result.analysis`가 비어 있지 않다.
- `error`가 `null`이다.

최종 실패 결과는 다음 조건을 모두 만족해야 한다.

- `openai_state`가 최종 실패 상태이다.
- `result`가 `null`이다.
- `error`가 존재하고 `retryable`이 `false`이다.

| `error` 필드 | 타입 | 필수 | 의미 |
| --- | --- | --- | --- |
| `scope` | string | 예 | 실패 구간 |
| `code` | string | 예 | 시스템 간 안정적으로 공유할 오류 코드 |
| `message` | string | 예 | 운영 진단용 설명 |
| `retryable` | boolean | 예 | 최종 실패 통지에서는 `false` |
| `details` | object 또는 null | 아니요 | 민감정보를 제외한 진단 정보 |

```json
{
  "job_id": 42,
  "external_request_id": "de305d54-75b4-431b-adb2-eb6b9e546014",
  "openai_response_id": null,
  "openai_state": "failed",
  "postprocess_state": "notify_in_progress",
  "result": null,
  "error": {
    "scope": "openai",
    "code": "LLM_RETRY_EXHAUSTED",
    "message": "LLM request failed after the configured attempts",
    "retryable": false,
    "details": null
  },
  "occurred_at": "2026-08-17T10:05:00Z"
}
```

### 허용하지 않는 조합

| 조합 | 처리 원칙 |
| --- | --- |
| `completed`인데 `result`가 `null` | 완료하지 않음 |
| `result`와 `error`가 동시에 존재 | 기존 상태 유지 |
| 실패 상태인데 `result`가 존재 | 기존 상태 유지 |
| 실패 상태인데 `error`가 `null` | 실패를 확정하지 않음 |
| 잘못된 `external_request_id` | 요청이나 리포트를 임의로 생성하지 않음 |

삭제, 재시도와 DLQ 판정은 [실패 처리 정책](failure-policy.md)에서 관리한다.

## 중복과 순서 역전 목표

| 상황 | 처리 원칙 |
| --- | --- |
| 동일 성공 결과 재수신 | 기존 `DONE`과 리포트 유지 |
| 동일 실패 결과 재수신 | 기존 `FAILED`와 실패 정보 유지 |
| `DONE` 이후 실패 도착 | `DONE` 유지, 충돌 기록 |
| `FAILED` 이후 성공 도착 | `FAILED` 유지, 충돌 기록 |
| 서로 다른 성공 결과 도착 | 최초 반영 결과 유지, 충돌 기록 |

이 표는 아직 Pizza result consumer에 구현되지 않았다.

## 호환성

다음 변경은 consumer가 새 형식을 먼저 처리할 수 있게 배포한 뒤 producer를 변경한다.

- 필수 필드 추가
- 필드 이름이나 타입 변경
- enum 값의 의미 변경
- 기존 필드 제거
- nullable 필드를 non-null로 변경

선택 필드 추가는 기존 consumer가 알 수 없는 필드를 무시할 때만 호환된다. `openai_request.schema_version`이 바뀌면 Pickle은 지원 여부를 명시적으로 판정해야 한다.

계약 변경 시 Pizza와 Pickle의 DTO, 대표 JSON 예제와 contract test를 함께 검토한다. 자동 contract 검증 방식은 [Pizza #18](https://github.com/dawwson/psycho.pizza/issues/18)에서 정립한다.
