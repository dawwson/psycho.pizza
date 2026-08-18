# 프로젝트 문서

이 디렉터리는 코드만으로 파악하기 어려운 시스템 흐름, 서비스 간 계약과 중요한 기술 결정의 이유를 기록합니다.

## 문서 지도

| 궁금한 점 | 문서 |
| --- | --- |
| Pizza–Pickle 분석 파이프라인은 어떻게 동작하는가? | [분석 파이프라인](analysis-pipeline/) |
| 분석 요청의 상태와 전이 조건은 무엇인가? | [분석 요청 lifecycle](analysis-pipeline/lifecycle.md) |
| Pizza와 Pickle이 어떤 메시지를 교환하는가? | [Pizza–Pickle 메시지 계약](analysis-pipeline/message-contract.md) |
| 오류, 재시도와 중복 메시지를 어떻게 처리하는가? | [분석 실패 처리 정책](analysis-pipeline/failure-policy.md) |
| 왜 현재 설계를 선택했는가? | [Architecture Decision Records](adr/) |
| AWS 리소스 이름은 어떤 규칙을 따르는가? | [AWS Naming Convention](AWS-Naming-Convention.md) |

## 작성 원칙

- 한국어를 기본으로 하고 코드 식별자와 기술 용어는 원문을 유지합니다.
- 현재 구현과 후속 PR에서 적용할 목표 정책을 구분합니다.
- 한 문서는 하나의 중심 질문에 답하고 세부 내용은 정본 문서로 연결합니다.
- 구현되지 않은 세부 알고리즘과 설정값은 담당 PR의 코드와 테스트를 작성할 때 결정합니다.
- 최종 수정일과 변경 이력은 문서에 반복하지 않고 Git 이력으로 확인합니다.

구체적인 문서 작성 및 동기화 규칙은 [AGENTS.md](../AGENTS.md)를 따릅니다.
