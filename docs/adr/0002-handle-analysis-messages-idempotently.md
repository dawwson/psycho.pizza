# 0002. 분석 메시지를 멱등하게 처리

- 상태: Accepted
- 결정일: 2026-08-17

## 맥락

Pizza와 Pickle은 SQS request queue와 response queue로 분석 요청과 결과를 교환합니다. SQS는 메시지를 최소 한 번 전달하므로 consumer가 메시지를 처리한 뒤 삭제하기 전에 종료되거나 [`visibility timeout`](#visibility-timeout) 안에 처리를 마치지 못하면 같은 메시지가 다시 전달될 수 있습니다.

Pizza의 DB 상태 변경, Pickle의 LLM 호출과 SQS 메시지 삭제는 하나의 분산 트랜잭션으로 묶이지 않습니다. producer 측에서 중복을 완전히 방지하려고 해도 다음 경계 장애를 제거할 수 없습니다.

- SQS 전송은 성공했지만 producer가 성공 응답을 받지 못함
- consumer가 DB commit을 마친 뒤 메시지를 삭제하기 전에 종료됨
- [`visibility timeout`](#visibility-timeout)이 끝나 같은 메시지를 다른 worker가 수신함
- 성공 결과와 지연된 실패 결과가 서로 다른 순서로 도착함

exactly-once 전달을 별도로 구축하는 대신 중복 전달을 정상 상황으로 받아들이고, 동일 요청을 여러 번 처리해도 영속 결과가 달라지지 않도록 해야 합니다.

## 결정

SQS의 최소 한 번 전달(at-least-once delivery)을 전제로 Pizza와 Pickle consumer를 멱등하게 설계합니다.

- Pizza가 발급한 `analysisRequestId`를 request와 response 전 구간의 안정적인 멱등성 식별자로 사용합니다.
- 메시지에서는 이 값을 현재 계약 필드인 `external_request_id`로 전달합니다.
- Pickle은 `external_request_id`가 같은 작업을 하나의 영속 Job으로 수렴시킵니다.
- 동일 요청의 재수신만으로 완료된 LLM 작업을 새로 생성하지 않습니다.
- Pizza는 최초로 확정한 `DONE` 또는 `FAILED`를 유지하며 같은 결과를 다시 받아도 상태와 리포트를 중복 변경하지 않습니다.
- 서로 충돌하는 종료 결과가 도착하면 먼저 영속화된 종료 결과를 유지하고 충돌을 운영 기록으로 남깁니다.
- consumer는 영속 상태 반영이나 이미 처리된 메시지임을 확인한 뒤에만 메시지를 삭제합니다.
- 일시적 오류에서는 메시지를 삭제하지 않아 SQS 재전달 또는 애플리케이션 재시도가 가능하게 합니다.
- 잘못된 메시지는 제한 없이 재시도하지 않고 queue의 [`redrive policy`](#redrive-policy)에 따라 DLQ로 격리합니다.

메시지 필드와 성공·실패 판정 조건은 [Pizza–Pickle 메시지 계약](../analysis-pipeline/message-contract.md), 오류 분류와 재시도 한도는 [분석 실패 처리 정책](../analysis-pipeline/failure-policy.md)에 정의합니다.

## 결과

### 이점

- 프로세스 종료와 [`visibility timeout`](#visibility-timeout)에 따른 정상적인 중복 전달을 안전하게 처리할 수 있습니다.
- producer와 consumer 사이에 분산 트랜잭션을 추가하지 않고 최종 상태를 일관되게 유지할 수 있습니다.
- 같은 요청 ID를 기준으로 Pizza와 Pickle의 로그, DB 행과 queue 메시지를 추적할 수 있습니다.
- retry와 recovery가 새 분석 요청을 만들지 않고 기존 요청에 수렴합니다.

### 비용과 한계

- 각 consumer가 중복, 종료 상태 충돌과 동시 실행을 명시적으로 판정해야 합니다.
- Pickle의 작업 유일성 및 Pizza의 상태 전이는 DB constraint와 transaction으로 보호해야 합니다.
- 최초 종료 결과를 유지하므로 잘못 확정된 결과를 자동으로 뒤집지 않습니다.

### 후속 영향

- Pizza 결과 consumer는 동일 성공·실패와 충돌 결과를 구분해야 합니다.
- Pickle은 동시에 같은 요청을 받아도 `external_request_id` 기준으로 하나의 Job에 수렴해야 합니다.
- 요청·결과 queue에 DLQ와 유한한 `maxReceiveCount`를 설정하고 장애 복구 테스트로 검증해야 합니다.
- 멱등성 식별자 이름을 변경할 경우 기존 `external_request_id`와의 호환 기간을 별도로 설계해야 합니다.

## 용어

| 영어 원문 | 의미 |
| --- | --- |
| <a id="visibility-timeout"></a>`visibility timeout` | consumer가 메시지를 처리하는 동안 같은 메시지가 다른 consumer에게 보이지 않도록 숨기는 시간 |
| <a id="redrive-policy"></a>`redrive policy` | 처리되지 않은 메시지를 다시 전달할 최대 횟수와 한도 초과 시 이동할 DLQ를 지정하는 SQS 정책 |
