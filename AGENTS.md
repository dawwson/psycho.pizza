# Repository Guidelines

개발 작업은 [Engineering Workflow v0.2](docs/workflow/engineering-workflow-v0.2.md)를 따른다.

## Scope

- 기존 사용자 변경을 보존하고 현재 작업과 무관한 변경을 섞지 않는다.
- 명시적인 요청 없이 Box를 수정하거나 기존 Box–Pizza API 계약을 변경하지 않는다.
- Pizza–Pickle 메시지 계약을 변경할 때 양쪽 저장소의 관련 DTO, 소비 코드와 테스트를 확인한다.

## Architecture

- Domain이 infrastructure에 의존하지 않게 한다.
- 외부 시스템 연동은 기존 port 또는 infrastructure 경계를 따른다.
- 현재 Goal에 필요하지 않은 architecture 변경이나 대규모 refactoring을 수행하지 않는다.

## Safety

- secret을 저장소에 추가하거나 로그에 출력하지 않는다.
- 적용된 Flyway migration을 수정하지 않는다.
- destructive migration은 승인과 복구 계획 없이 수행하지 않는다.

## Git Authority

Git staging, commit, push, PR 생성, merge, tag와 release는 사람이 수행한다.
