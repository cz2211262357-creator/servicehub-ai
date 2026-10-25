FROM mirror.gcr.io/library/node:26-alpine@sha256:6f08a523443ddedf52941874c203475b0ad503e859ad70cb26ab8be9a517dbd5 AS build
WORKDIR /project/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,id=servicehub-java-npm,target=/project/.cache/npm npm ci --cache /project/.cache/npm
COPY frontend ./
RUN npm run build
FROM mirror.gcr.io/library/nginx:1.28-alpine@sha256:a8b39bd9cf0f83869a2162827a0caf6137ddf759d50a171451b335cecc87d236
COPY deploy/java-nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /project/frontend/dist /usr/share/nginx/html
HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 CMD wget -q -O /dev/null http://127.0.0.1/health || exit 1
