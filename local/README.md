# Box·Pizza·Pickle 로컬 실행

세 저장소를 같은 부모 디렉터리에 두고 Docker Desktop을 실행한다. Node.js와 Python은 컨테이너에 포함한다.

```text
psycho/
├── psycho.box/
├── psycho.pizza/
└── psycho.pickle/
```

`psycho.pizza`에서 다음 명령을 실행한다. 처음 실행할 때 이미지와 의존성을 내려받고 Pizza를 빌드하므로 시간이 걸린다.

```bash
docker compose -f compose.local.yml up -d
docker compose -f compose.local.yml ps
docker compose -f compose.local.yml logs -f pizza pickle box
```

| 서비스 | 주소 |
| --- | --- |
| Box | http://localhost:3000 |
| Pizza Swagger | http://localhost:8080/swagger-ui/index.html |
| Pizza health | http://localhost:8080/actuator/health |
| Pickle health | http://localhost:8000/health |
| 인증 메일함 (Mailpit) | http://localhost:8025 |
| SQS 관리 화면 (ElasticMQ) | http://localhost:9325 |
| PostgreSQL | localhost:5433, 사용자 `psycho`, 비밀번호 `local-only` |

DB는 `pizza`와 `pickle`로 분리한다. 표의 자격 증명은 이 로컬 환경 전용이며 모든 공개 포트는 loopback에만 바인딩한다. Box의 서버는 `http://pizza:8080`을 호출한다. 저장소의 운영 환경 파일 대신 Compose에서 지정한 값을 사용한다. Spring은 기존 `prod` 프로필을 재사용하지만 DB, SQS, 메일, 인증 설정은 로컬 값으로 덮어쓴다.

## 실제 AI 분석

기본 실행은 OpenAI 키 없이 Pickle API와 DB 연결을 확인하는 모드이다. **Pickle worker는 기본적으로 꺼져 있으므로 분석 요청은 SQS에 대기한다.** 실제 분석에는 OpenAI API 키와 worker 활성화가 필요하며 API 사용료가 발생한다.

키를 shell 환경변수 `LOCAL_OPENAI_API_KEY`에 넣은 후 다음 명령을 실행한다. 키를 저장소 파일이나 명령 기록에 직접 작성하지 않는다.

```bash
LOCAL_ENABLE_WORKER=true docker compose -f compose.local.yml up -d pizza pickle
```

`LOCAL_ENABLE_WORKER=true`는 이후 `up` 명령에도 유지한다. 키 없이 worker를 켜면 Pickle 시작이 실패한다. OpenAI webhook은 로컬에서 외부 공개하지 않으며, 이 구성은 SQS를 통한 분석 요청과 결과 통지를 대상으로 한다.

## 수정 반영과 종료

Box 개발 서버는 `src` 변경을 반영한다. Box의 패키지·설정 파일을 바꾸면 `restart box`로 다시 복사하고 의존성을 설치한다. Pizza와 Pickle 소스를 바꾼 뒤에는 해당 서비스를 재시작한다.

```bash
docker compose -f compose.local.yml restart pizza pickle
docker compose -f compose.local.yml stop
# 중지한 환경을 다시 실행한다.
docker compose -f compose.local.yml up -d
```

DB와 의존성 캐시는 Docker volume에 보관한다. `stop`과 일반 `down`은 DB volume을 삭제하지 않는다. SQS 메시지와 로컬 메일은 임시 데이터이며 컨테이너 재생성 시 사라질 수 있다. `down -v`는 DB까지 삭제하므로 일상적인 종료 명령으로 사용하지 않는다.

## 검증 범위

각 앱의 health와 Box 화면 응답은 Compose healthcheck로 확인한다. 실제 OpenAI 분석 완료는 유효한 키를 넣고 별도로 확인해야 한다.
