VERSION ?= 0.1.0-SNAPSHOT
PACKAGE_NAME := hify-$(VERSION)

.PHONY: start stop restart build clean package

start:
	./start.sh

stop:
	./stop.sh

restart: stop start

build:
	cd backend && mvn clean package
	cd frontend && npm ci --no-audit --no-fund && npm run build

clean:
	cd backend && mvn clean
	rm -rf frontend/dist dist-packages

package: build
	@set -eu; \
	stage=$$(mktemp -d); \
	mkdir -p "$$stage/$(PACKAGE_NAME)/backend" "$$stage/$(PACKAGE_NAME)/web" "$$stage/$(PACKAGE_NAME)/deploy" dist-packages; \
	cp backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar "$$stage/$(PACKAGE_NAME)/backend/hify.jar"; \
	cp -R frontend/dist/. "$$stage/$(PACKAGE_NAME)/web/"; \
	cp deploy/nginx.conf "$$stage/$(PACKAGE_NAME)/deploy/"; \
	cp README.md "$$stage/$(PACKAGE_NAME)/"; \
	tar -C "$$stage" -czf "$(CURDIR)/dist-packages/$(PACKAGE_NAME).tar.gz" "$(PACKAGE_NAME)"; \
	rm -rf "$$stage"; \
	echo "Created dist-packages/$(PACKAGE_NAME).tar.gz"
