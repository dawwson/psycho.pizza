# 분석 요청 lifecycle

## 목적

이 문서는 Pizza가 소유하는 분석 요청 상태의 의미와 전이 조건을 정의합니다. 상태는 내부 thread의 실행 여부가 아니라 사용자가 조회할 수 있는 분석 요청의 처리 단계를 나타냅니다.

Pizza DB의 `analysis_request`를 lifecycle의 기준으로 사용하는 이유는 [ADR 0001](../adr/0001-use-pizza-db-as-analysis-lifecycle-source.md), 요청별 transaction으로 발행하는 이유는 [ADR 0003](../adr/0003-dispatch-analysis-requests-in-individual-transactions.md)에 기록한다.

## 현재 구현

현재 흐름은 다음과 같다.

```mermaid
sequenceDiagram
    participant Client
    participant PizzaAPI
    participant PizzaDB
    participant Dispatcher
    participant RequestQueue
    participant Pickle
    participant ResponseQueue

    Client->>PizzaAPI: 분석 요청 생성
    PizzaAPI->>PizzaDB: AnalysisRequest(QUEUED), AnalysisReport 저장
    loop 최대 batch 크기
        Dispatcher->>PizzaDB: 발행 가능한 QUEUED 요청 1개 선점
        Dispatcher->>Dispatcher: 분석 입력 계산
        Dispatcher->>RequestQueue: 요청 메시지 전송
        Dispatcher->>PizzaDB: 성공, 재시도 또는 실패 상태 저장
    end
    RequestQueue-->>Pickle: 요청 전달
    Pickle-->>ResponseQueue: 성공 결과 통지
    ResponseQueue-->>PizzaDB: RUNNING to DONE, report 갱신
```

현재 코드에서 확인되는 동작은 다음과 같다.

| 상황 | 현재 동작 |
| --- | --- |
| 요청 생성 | 요청과 초기 리포트를 저장하고 `QUEUED` 반환 |
| Pizza Dispatcher가 polling | `next_retry_at`이 없거나 현재 시각이 지난 `QUEUED` 요청을 PostgreSQL row lock으로 하나씩 선점 |
| 발행 처리 단위 | 한 번의 실행에서 최대 10개를 처리하되 요청마다 별도 transaction 사용 |
| SQS 전송 성공 | `RUNNING`으로 변경하고 `started_at` 기록 |
| 입력 생성 또는 영구 SQS 오류 | 즉시 `FAILED`로 변경하고 명료한 한국어 실패 이유 저장 |
| 일시적 SQS 오류 | 최대 3회 안에서 5초, 10초 후 재시도하고 소진 시 `FAILED`로 변경 |
| 정체된 `RUNNING` | 10분이 지난 요청만 선점해 시도가 남으면 `QUEUED`, 소진됐으면 `FAILED`로 변경 |
| 성공 결과 수신 | `RUNNING`을 `DONE`으로 변경하고 리포트에 결과 저장 |
| 애플리케이션 재시작 | 상태를 일괄 초기화하지 않고 저장된 retry 시각과 stale 기준으로 처리 대상을 판정 |

현재 성공 결과 소비자는 성공 payload만 처리합니다. Pickle의 최종 실패 통지, 중복 성공 결과와 종료 상태에 늦게 도착한 메시지를 안전하게 수렴시키는 동작은 아직 구현되어 있지 않습니다.

## 상태 의미

| 상태 | 의미 | 진입 시점 | 종료 시 기록 |
| --- | --- | --- | --- |
| `QUEUED` | Pizza DB에 요청이 저장됐으며 Pickle 전달을 기다리거나 다음 전송 시각을 기다리는 상태 | 요청 생성 또는 제한된 복구 | 전송 시도 정보 갱신 |
| `RUNNING` | SQS request queue 전송이 성공해 Pickle의 최종 결과를 기다리는 상태 | Dispatcher의 전송 성공 | `started_at` 기록 |
| `DONE` | 성공 결과가 반영되고 분석 리포트를 조회할 수 있는 종료 상태 | 유효한 성공 결과 반영 | `completed_at` 기록, 오류 정보 제거 |
| `FAILED` | Pizza 또는 Pickle에서 복구 불가능한 최종 실패로 판정한 종료 상태 | 재시도 소진 또는 최종 실패 결과 반영 | `completed_at`과 [실패 처리 정책](failure-policy.md#실패-정보-저장-범위)에 정의된 실패 정보 기록 |

`DONE`과 `FAILED`는 종료 상태입니다. 동일 메시지의 재처리는 기존 결과를 유지해야 하며 새로운 상태 전이나 리포트 중복 갱신을 만들지 않아야 합니다.

## 상태 전이

Pizza request 발행과 stale 복구 전이는 현재 구현되어 있다. Pickle 최종 실패 반영과 종료 상태의 중복 결과 수용은 후속 result consumer 작업의 목표이다.

```mermaid
stateDiagram-v2
    [*] --> QUEUED: 요청과 초기 리포트 저장
    QUEUED --> QUEUED: 일시적 전송 실패 후 재시도 예약
    QUEUED --> RUNNING: SQS 전송 성공
    QUEUED --> FAILED: 전송 재시도 소진 또는 영구 오류
    RUNNING --> RUNNING: 진행 확인 또는 중복 요청 전달
    RUNNING --> QUEUED: 정체 작업 재전송 결정
    RUNNING --> DONE: 성공 결과 반영
    RUNNING --> FAILED: Pickle 최종 실패 반영 또는 발행 시도 소진 후 stale 판정
    DONE --> DONE: 동일 성공 결과 재수신
    FAILED --> FAILED: 동일 실패 결과 재수신
```

### 허용 전이

| 현재 상태 | 다음 상태 | 주체 | 조건 | 구현 상태 |
| --- | --- | --- | --- | --- |
| 없음 | `QUEUED` | Pizza API | 요청과 초기 리포트 저장이 함께 성공 | 구현 |
| `QUEUED` | `QUEUED` | Pizza Dispatcher | 재시도 가능한 전송 오류이며 최대 시도에 도달하지 않음 | 구현 |
| `QUEUED` | `RUNNING` | Pizza Dispatcher | SQS request queue 전송 성공 | 구현 |
| `QUEUED` | `FAILED` | Pizza Dispatcher | 입력 생성 또는 영구 발행 오류, 발행 재시도 소진 | 구현 |
| `RUNNING` | `QUEUED` | Pizza recovery | 정체 작업 기준을 만족하고 최대 발행 시도 횟수가 남음 | 구현 |
| `RUNNING` | `DONE` | Pizza result consumer | 요청 ID가 일치하는 유효한 성공 결과를 최초 반영 | 구현 |
| `RUNNING` | `FAILED` | Pizza recovery | 최대 발행 시도를 사용한 요청이 stale로 판정됨 | 구현 |
| `RUNNING` | `FAILED` | Pizza result consumer | Pickle의 최종 실패 결과 반영 | 후속 작업 |

자기 전이는 상태 변경이 아니라 동일 작업의 재확인 또는 중복 메시지 수신을 표현합니다. 시도 횟수나 마지막 수신 시각 같은 부가 정보는 바뀔 수 있지만 lifecycle 결과는 바뀌지 않습니다.

### 허용하지 않는 전이

| 전이 | 처리 원칙 |
| --- | --- |
| `QUEUED` → `DONE` | 전달과 실행 단계를 건너뛴 결과이므로 자동 완료하지 않음 |
| `DONE` → `RUNNING` 또는 `QUEUED` | 완료된 요청을 다시 열지 않음 |
| `FAILED` → `RUNNING` 또는 `QUEUED` | 최종 실패 요청을 자동으로 다시 열지 않음 |
| `DONE` ↔ `FAILED` | 먼저 확정된 종료 결과를 다른 종료 상태로 덮어쓰지 않음 |

최종 실패 이후 사용자가 다시 분석을 요청하면 기존 요청을 되살리지 않고 새로운 `analysisRequestId`를 생성합니다.

## 구성요소별 책임

### Pizza API

- 요청과 초기 리포트를 같은 트랜잭션에서 저장합니다.
- 외부 응답에는 기존 상태 이름과 필드를 유지합니다.
- 요청 생성 성공은 Pickle 전달 성공을 의미하지 않습니다.

### Pizza DB Dispatcher

- Dispatcher는 한 번의 실행에서 처리할 수를 제한하고, 요청별 transaction에서 처리 가능한 `QUEUED` 요청을 하나씩 선점한다.
- Dispatcher는 분석 입력을 계산한 뒤 그 결과로 request 메시지를 구성합니다.
- 전송 시도와 다음 재시도 가능 시각을 영속화합니다.
- SQS 전송 성공 후 `RUNNING`으로 전환합니다.
- 영구 오류 또는 최대 시도 초과를 `FAILED`로 확정합니다.

### Pickle

- `analysisRequestId`에 대응하는 외부 요청 ID를 작업 식별자로 유지합니다.
- 동일 요청을 다시 받아도 별개의 LLM 작업을 무제한 생성하지 않습니다.
- 성공 또는 최종 실패 결과를 Pizza에 통지합니다.

### Pizza result consumer

- 성공과 최종 실패 결과를 요청 ID 기준으로 반영합니다.
- 같은 결과가 반복 전달돼도 종료 상태와 리포트를 중복 변경하지 않습니다.
- 종료 상태와 충돌하는 결과는 기존 상태를 덮어쓰지 않고 기록 가능한 형태로 남깁니다.

## 정체 작업 복구 원칙

정체 작업(stale job)은 정상 처리 제한 시간을 초과했지만 종료 상태로 전환되지 않은 `QUEUED` 또는 `RUNNING` 분석 요청이다. 상태 이름만으로 정체 여부를 판단하지 않고 마지막 시도 시각, 다음 시도 가능 시각, 상태 체류 시간과 최대 시도 횟수를 함께 사용한다.

- `QUEUED`: `next_retry_at`이 현재 시각 이전인 요청만 Dispatcher 대상이 된다.
- `RUNNING`: 정상 처리 제한 시간을 넘긴 요청만 복구 후보가 된다.
- 최대 발행 시도 횟수가 남은 `RUNNING`은 같은 요청 ID를 유지한 채 `QUEUED`로 전환한다.
- 최대 발행 시도 횟수를 사용한 `RUNNING`이 stale 상태가 되면 `FAILED`로 종료한다.
- 애플리케이션 시작만을 이유로 모든 `RUNNING`을 즉시 `QUEUED`로 바꾸지 않는다.

시도 횟수의 계산, recovery 선점, 재발행 책임과 초기 설정값은 [실패 처리 정책](failure-policy.md#stale-job-복구)에서 정의한다.

## 남은 구현 차이

Pizza request 발행 retry와 stale 복구는 현재 구현되어 있다. 목표 lifecycle과 현재 코드의 남은 차이는 다음과 같다.

| 항목 | 현재 | 목표와 담당 작업 |
| --- | --- | --- |
| Pickle 최종 실패 | 소비 계약 없음 | 실패 결과를 받아 `FAILED` 반영 |
| 중복 결과 | 상태 전이 예외 발생 가능 | 동일 결과를 멱등하게 수용 |

Pickle 최종 실패와 중복 결과 처리 목표는 후속 result consumer 작업이 완료되기 전까지 현재 동작으로 간주하지 않는다.
