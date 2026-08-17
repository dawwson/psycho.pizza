# Pizza–Pickle 메시지 계약

## 목적

이 문서는 Pizza와 Pickle이 SQS를 통해 교환하는 분석 요청과 결과 메시지의 의미, 필수 필드와 호환성 규칙을 정의합니다. 코드의 언어별 타입 이름이 아니라 실제 JSON body를 계약의 기준으로 사용합니다.

Box가 사용하는 Pizza REST API는 이 내부 메시지 계약의 영향을 받지 않습니다. 내부 메시지를 변경하기 위해 기존 Box–Pizza API의 상태 이름이나 응답 필드를 변경하지 않습니다.

## 책임 경계

| 구성요소 | 책임 |
| --- | --- |
| Pizza | 분석 요청 ID 발급, 분석 입력 생성, request queue 발행, lifecycle과 최종 리포트 저장 |
| SQS request queue | Pizza가 생성한 분석 요청을 Pickle에 한 번 이상 전달 |
| Pickle | 요청 ID 기준 작업 소유, LLM 호출, 작업·시도·결과 저장, 최종 결과 통지 |
| SQS response queue | Pickle의 성공 또는 최종 실패 결과를 Pizza에 한 번 이상 전달 |
| Pizza result consumer | 요청 ID 검증, 성공·실패 결과의 멱등 반영, 처리 완료 메시지 삭제 |

SQS 메시지는 중복되거나 지연될 수 있습니다. 메시지 한 건의 수신 여부가 아니라 `external_request_id`와 각 시스템의 영속 상태를 처리 기준으로 사용합니다.

## 공통 규칙

- JSON 필드명은 `snake_case`를 사용합니다.
- UUID는 하이픈을 포함한 표준 문자열로 직렬화합니다.
- 시각은 timezone을 포함한 ISO 8601 문자열로 직렬화합니다.
- `external_request_id`는 Pizza가 발급한 `analysisRequestId`와 같은 값입니다.
- 같은 분석 요청을 재전송할 때 새로운 `external_request_id`를 만들지 않습니다.
- Pickle의 `job_id`는 Pickle 내부 DB 식별자이며 Pizza lifecycle 식별자로 사용하지 않습니다.
- 계약에 명시된 nullable 필드는 JSON `null`을 허용합니다. 필수 필드는 누락과 `null`을 모두 허용하지 않습니다.

## 분석 요청 메시지

### Body schema

| 필드 | 타입 | 필수 | 의미 |
| --- | --- | --- | --- |
| `external_request_id` | UUID string | 예 | Pizza의 `analysisRequestId`; 중복 요청 판정 기준 |
| `result_fetch_url` | HTTPS URL string | 예 | 현재 Pickle schema 호환을 위해 유지하는 결과 조회 URL |
| `openai_request` | object | 예 | Pizza가 계산한 분석 입력 |
| `tenant` | string | 아니요 | Pickle의 tenant 분리가 필요할 때 사용하는 식별자 |
| `context` | object | 아니요 | 추적에 필요한 부가 정보; 처리 의미를 바꾸는 필드를 두지 않음 |

`openai_request`는 다음 최상위 필드를 포함합니다.

| 필드 | 타입 | 필수 | 의미 |
| --- | --- | --- | --- |
| `schema_version` | string | 예 | 분석 입력 schema 버전 |
| `context` | object | 예 | 워크스페이스와 스프린트 식별 정보 |
| `summary` | object | 예 | 분석 시점의 태스크 상태 요약 |
| `metrics` | object | 예 | 완료, 안정성과 흐름 분석 지표 |

### 예제

```json
{
  "external_request_id": "de305d54-75b4-431b-adb2-eb6b9e546014",
  "result_fetch_url": "https://api.example.com/api/v1/analysis/requests/de305d54-75b4-431b-adb2-eb6b9e546014/result",
  "openai_request": {
    "schema_version": "v1",
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
  "context": {
    "workspace_id": "8a1d8f43-8cb5-45f2-8ab1-53c0b0c68277",
    "analysis_request_id": "de305d54-75b4-431b-adb2-eb6b9e546014"
  }
}
```

### 소비 규칙

- Pickle은 `external_request_id`가 처음이면 작업을 생성합니다.
- 같은 `external_request_id`를 다시 받으면 저장된 작업을 조회하고 현재 단계에서 필요한 처리만 계속합니다.
- 이미 최종 결과가 저장된 요청은 새로운 LLM 호출을 만들지 않습니다.
- 필수 필드 누락, 잘못된 UUID 또는 지원하지 않는 분석 입력 버전은 LLM 호출 전에 거부합니다.
- 알 수 없는 추가 필드는 호환성을 위해 무시할 수 있지만 처리 의미를 변경하는 데 사용하지 않습니다.

`result_fetch_url`은 현재 Pickle의 request schema가 필수로 요구하지만 실제 LangChain 실행 경로에서는 사용하지 않습니다. Pizza producer도 현재 placeholder URL을 생성하므로 유효한 조회 계약으로 간주할 수 없습니다. 제거하거나 실제 endpoint로 전환하기 전까지는 호환 필드로만 유지하며 Pickle은 이 URL에 의존해 분석을 완료하지 않습니다.

## 결과 메시지 공통 구조

성공과 최종 실패는 같은 공통 메시지 구조를 사용합니다.

| 필드 | 타입 | 필수 | 의미 |
| --- | --- | --- | --- |
| `job_id` | integer | 예 | Pickle 내부 작업 ID; 추적용 |
| `external_request_id` | UUID string | 예 | Pizza의 `analysisRequestId` |
| `openai_response_id` | string 또는 null | 예 | LLM 응답 식별자; 응답 생성 전 실패하면 `null` |
| `openai_state` | string | 예 | Pickle이 기록한 LLM 최종 상태 |
| `postprocess_state` | string | 예 | Pickle 후처리 상태; Pizza lifecycle 결과 판정 기준으로 단독 사용하지 않음 |
| `result` | object 또는 null | 예 | 성공 결과; 최종 실패이면 `null` |
| `error` | object 또는 null | 예 | 최종 실패 정보; 성공이면 `null` |
| `occurred_at` | ISO 8601 string | 예 | Pickle이 통지 payload를 생성한 시각 |

### 성공 결과

성공 결과는 다음 조건을 모두 만족합니다.

- `openai_state`가 `completed`입니다.
- `result`가 존재하고 `result.analysis`가 비어 있지 않은 문자열입니다.
- `error`가 `null`입니다.

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

Pizza는 최초 유효 성공 결과를 반영해 요청을 `DONE`으로 전환하고 분석 리포트에 `job_id`와 `result.analysis`를 저장합니다.

### 최종 실패 결과

최종 실패 결과는 자동 재시도를 더 수행하지 않기로 Pickle이 확정한 뒤에만 보냅니다.

- `openai_state`는 `failed` 또는 `cancelled` 같은 최종 실패 상태입니다.
- `result`는 `null`입니다.
- `error`는 실패 정보를 포함합니다.

| `error` 필드 | 타입 | 필수 | 의미 |
| --- | --- | --- | --- |
| `scope` | string | 예 | 실패 구간; 예: `openai`, `postprocess` |
| `code` | string | 예 | Pizza가 저장할 수 있는 안정적인 오류 코드 |
| `message` | string | 예 | 운영 진단용 설명; 사용자에게 그대로 노출하지 않음 |
| `retryable` | boolean | 예 | 이 통지 시점에는 항상 `false` |
| `details` | object 또는 null | 아니요 | 민감정보를 제외한 추가 진단 정보 |

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

Pizza는 최초 유효 최종 실패를 반영해 요청을 `FAILED`로 전환하고 오류 코드와 설명을 저장합니다.

### 허용하지 않는 조합

| 조합 | 처리 원칙 |
| --- | --- |
| `completed`인데 `result`가 `null` | 잘못된 payload로 처리하고 완료하지 않음 |
| 성공 `result`와 `error`가 동시에 존재 | 모호한 payload로 처리하고 완료하지 않음 |
| 실패 상태인데 `result`가 존재 | 모호한 payload로 처리하고 기존 상태를 유지 |
| 실패 상태인데 `error`가 `null` | 잘못된 payload로 처리하고 실패를 확정하지 않음 |
| `external_request_id`가 UUID가 아님 | 역직렬화 또는 검증 실패로 처리 |
| Pizza에 없는 요청 ID | 임의의 요청이나 리포트를 생성하지 않음 |

잘못된 payload를 즉시 삭제할지 재시도 후 DLQ로 보낼지는 실패 처리 정책에서 정의합니다.

## 중복과 순서 역전

- 동일 성공 결과가 반복되면 최초 결과를 유지하고 메시지 처리는 성공으로 종료합니다.
- 동일 실패 결과가 반복되면 기존 `FAILED` 상태와 오류 정보를 유지합니다.
- `DONE` 이후 도착한 실패 결과와 `FAILED` 이후 도착한 성공 결과는 종료 상태를 덮어쓰지 않습니다.
- 같은 요청 ID에 서로 다른 성공 결과가 도착하면 최초 반영 결과를 유지하고 충돌을 운영 기록으로 남깁니다.
- Pizza가 안전하게 처리한 메시지만 response queue에서 삭제합니다.

## 호환성 규칙

다음 변경은 양쪽 소비자가 새 형식을 처리할 수 있도록 먼저 배포한 뒤 producer를 변경합니다.

- 필수 필드 추가
- 필드 이름 또는 타입 변경
- enum 값의 의미 변경
- 기존 필드 제거
- nullable 필드를 non-null로 변경

선택 필드 추가는 기존 소비자가 알 수 없는 필드를 무시할 때만 호환됩니다. `openai_request.schema_version`이 바뀌면 Pickle은 지원 여부를 명시적으로 판정해야 하며, 지원하지 않는 버전을 임의로 해석하지 않습니다.

## 현재 구현과 목표 계약의 차이

| 항목 | 현재 구현 | 목표 계약 |
| --- | --- | --- |
| 요청 식별자 | Pizza UUID가 `external_request_id`로 전달됨 | 현재 방식 유지 |
| `result_fetch_url` | Pizza가 placeholder URL 생성, Pickle schema는 필수 | 호환 필드로 취급하고 분석 완료가 의존하지 않음 |
| Pickle 중복 요청 | DB의 `external_request_id`로 기존 작업 조회 | 현재 방식 유지하고 모든 실행 경로에서 보장 |
| 성공 결과 | Pickle의 nullable `result`; Pizza는 non-null `ResultPayload` 요구 | 성공 조건과 `result.analysis` 검증 |
| 실패 결과 | Pickle은 `result=null`과 error를 만들 수 있으나 Pizza가 역직렬화하지 못함 | Pizza가 최종 실패 메시지 구조를 수용 |
| 결과 판정 | Pizza가 수신 payload를 모두 성공으로 처리 | `openai_state`, `result`, `error` 조합 검증 |
| 중복 결과 | 두 번째 완료에서 상태 전이 예외 가능 | 같은 결과를 멱등하게 수용 |
| 알 수 없는 요청 | 처리 예외 후 메시지가 queue에 남음 | 재시도와 DLQ 기준에 따라 명시적으로 처리 |

후속 구현이 완료되기 전에는 목표 계약을 현재 동작으로 간주하지 않습니다.
