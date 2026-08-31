# 0003. 분석 요청을 요청 단위 transaction으로 발행

- 상태: Accepted
- 결정일: 2026-08-31

## 맥락

Pizza의 DB Dispatcher는 하나의 transaction에서 여러 `QUEUED` 분석 요청을 선점한 뒤 입력을 생성하고 SQS request queue로 순차 발행한다. 이 구조에서는 한 요청의 SQS 지연이 batch 전체의 row lock 유지 시간을 늘린다. transaction이 rollback되면 앞서 발행에 성공한 요청의 DB 상태도 함께 rollback된다.

SQS 발행과 DB 상태 변경은 하나의 원자적 transaction으로 묶을 수 없다. SQS 발행 후 DB commit 전에 프로세스가 종료되면 메시지는 전달됐지만 요청은 `QUEUED`로 남아 다시 발행될 수 있다. 이 경계 장애를 수용하면서 batch 요청 사이의 transaction 결합을 줄여야 한다.

다음 대안을 검토했다.

| 대안 | 이점 | 비용과 한계 |
| --- | --- | --- |
| batch 단위 transaction | 현재 구조를 유지할 수 있음 | SQS 지연과 rollback 영향이 batch 전체에 미침 |
| 요청 단위 transaction | 요청 사이의 lock과 rollback 영향을 격리함 | SQS 발행과 DB commit 사이의 중복 가능성이 남음 |
| claim 후 transaction 밖에서 발행 | 외부 호출 중 row lock을 유지하지 않음 | claim 상태나 lease가 필요하며 중복 가능성은 남음 |
| Transactional Outbox | DB 변경과 발행 의도를 원자적으로 저장함 | 별도 schema, 발행·재시도·정리와 운영 책임이 추가됨 |
| 인메모리 queue와 worker thread | scheduler와 외부 호출을 분리함 | 작업이 유실될 수 있고 DB와 작업 소유권이 중복됨 |

## 결정

Dispatcher는 설정된 batch 크기만큼 반복하되, 한 transaction에서 분석 요청 하나만 선점하고 처리한다.

- 발행 가능한 `QUEUED` 요청 하나를 `FOR UPDATE SKIP LOCKED`로 선점한다.
- 같은 transaction에서 발행 시도를 기록하고 분석 입력을 생성한 뒤 SQS request queue로 발행한다.
- 발행 결과에 따라 요청을 `RUNNING`, 재시도 가능한 `QUEUED` 또는 `FAILED`로 변경한 뒤 commit한다.
- 한 요청의 실패나 rollback이 이미 처리한 다른 요청의 DB 상태에 영향을 주지 않게 한다.
- SQS 발행 성공 후 DB commit 전 종료로 발생할 수 있는 중복 발행은 허용한다.
- 중복 메시지는 [ADR 0002](0002-handle-analysis-messages-idempotently.md)에 따라 동일한 `analysisRequestId`로 멱등하게 처리한다.
- Transactional Outbox와 인메모리 queue·worker thread는 도입하지 않는다.

Transactional Outbox는 발행 유실을 줄일 수 있지만 outbox 저장·발행·재시도·정리와 운영 관측 책임이 추가된다. 현재 `analysis_request`가 발행 대기 상태와 재시도 정보를 영속화하고 중복 전달을 멱등하게 처리하므로 이 책임을 추가하지 않는다.

인메모리 queue와 worker thread는 프로세스 종료 시 작업이 유실될 수 있고 DB와 메모리 사이의 작업 소유권을 별도로 관리해야 한다. [ADR 0001](0001-use-pizza-db-as-analysis-lifecycle-source.md)에서 정한 DB source of truth와 책임이 중복되므로 다시 도입하지 않는다.

claim 후 transaction 밖에서 발행하려면 다른 Dispatcher의 중복 선점을 막는 상태나 lease가 필요하다. 이 방식도 SQS 발행 후 상태 저장 전 종료에 따른 중복을 제거하지 못하므로 선택하지 않는다.

## 결과

### 이점

- SQS 호출 중 유지하는 row lock을 현재 처리 중인 요청 하나로 제한한다.
- 한 요청의 실패가 같은 batch의 다른 요청 상태를 rollback하지 않는다.
- 기존 DB Dispatcher, 재시도 필드와 멱등성 식별자를 그대로 사용한다.
- 별도 outbox table, claim 상태와 인메모리 worker lifecycle을 관리하지 않는다.

### 비용과 한계

- SQS 호출이 끝날 때까지 현재 요청의 row lock을 유지한다.
- SQS 발행 성공 후 DB commit 전에 종료되면 같은 요청이 다시 발행될 수 있다.
- exactly-once 발행을 보장하지 않으며 producer와 consumer 사이의 분산 transaction을 구성하지 않는다.
- Pickle과 Pizza 결과 consumer가 동일한 `analysisRequestId`의 중복 메시지를 멱등하게 처리해야 한다.

### 후속 영향

- Dispatcher는 batch 전체가 아니라 요청 하나마다 transaction을 시작하고 종료해야 한다.
- 발행 retry와 stale 요청 복구는 [ADR 0001](0001-use-pizza-db-as-analysis-lifecycle-source.md)에 따라 `analysis_request`의 영속 상태를 기준으로 처리한다.
- 운영 중 SQS 지연으로 인한 DB lock 또는 발행 유실이 허용하기 어려운 문제로 확인되면 claim·lease 또는 Transactional Outbox를 새 ADR에서 검토한다.
- Transactional Outbox를 도입해 이 결정을 변경할 때는 기존 ADR을 수정하지 않고 대체 ADR을 작성한다.
