.PHONY: infra-up infra-down web-dev web-check server-run server-test check

infra-up:
	docker compose -f infra/compose.yaml up -d

infra-down:
	docker compose -f infra/compose.yaml down

web-dev:
	pnpm --filter @intelligent-recruitment/web dev

web-check:
	pnpm --filter @intelligent-recruitment/web lint
	pnpm --filter @intelligent-recruitment/web typecheck
	pnpm --filter @intelligent-recruitment/web test
	pnpm --filter @intelligent-recruitment/web build

server-run:
	cd services/recruitment-service && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local

server-test:
	cd services/recruitment-service && ./mvnw test

check: web-check server-test

