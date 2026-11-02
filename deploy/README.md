# 홈 서버 배포

## 1회 준비
1. Docker + Docker Compose 설치 (x86/ARM 모두 가능).
2. `cp .env.example .env` 후 값 입력. `.env`는 git에 올리지 않습니다.
3. Azure MySQL에서 덤프: `mysqldump -h 40.82.136.44 -u lvalue -p --single-transaction SNAP_SEARCH > initdb/01_dump.sql`
   (최초 `docker compose up` 시 자동 import. 이후엔 디렉터리 비워도 됨)
4. Cloudflare Zero Trust에서 터널 생성 → 토큰을 `.env`에 → Public hostname `www.toomuchoffside.site` → `http://nginx:80`.
5. `docker compose up -d` → `docker compose logs -f backend`로 기동 확인 → `curl localhost:8088/actuator/health`.

## 백엔드 코드 쪽에서 필요한 변경 (로드맵 1주차)
- `application-home.properties` 추가: datasource/키를 `${...}` 환경변수로만 받도록.
- `spring-boot-starter-actuator` 의존성 추가 (healthcheck용).
- `ImageStorage` 인터페이스 + `LocalFileStorage` 구현 (`IMAGE_STORAGE_ROOT`에 저장, `IMAGE_PUBLIC_BASE_URL`로 URL 생성).
- Dockerfile 베이스 이미지를 `eclipse-temurin:17-jre`로 (ARM 지원, openjdk 이미지는 deprecated).

## 프론트 코드 쪽에서 필요한 변경
- `next.config.js`: `output: "export"` → `output: "standalone"`.
- Dockerfile: standalone 산출물(`.next/standalone`, `.next/static`, `public`) 복사 후 `node server.js`.

## 자동 배포
GitHub Actions가 main push마다 GHCR에 이미지를 push하면 Watchtower가 5분 내 pull/재시작합니다.
Docker Hub를 계속 쓸 거면 `.env`의 `*_IMAGE`만 바꾸면 됩니다.

## 백업
```
docker exec snap-mysql mysqldump -u root -p"$MYSQL_ROOT_PASSWORD" --single-transaction SNAP_SEARCH | gzip > backup/$(date +%F).sql.gz
```
를 cron(매일 04:00)으로. 이미지 볼륨은 `docker run --rm -v snap-search_images:/src -v $PWD/backup:/dst alpine tar czf /dst/images.tgz -C /src .`
