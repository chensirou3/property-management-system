ARG NODE_BUILD_IMAGE=node:22.16-alpine
ARG WEB_RUNTIME_IMAGE=nginxinc/nginx-unprivileged:1.29-alpine

FROM ${NODE_BUILD_IMAGE} AS build
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM ${WEB_RUNTIME_IMAGE}
ARG PMS_SECURITY_REFRESH=manual
USER root
RUN echo "security-refresh=${PMS_SECURITY_REFRESH}" \
    && apk upgrade --no-cache \
    && apk add --no-cache --upgrade \
        'libcrypto3>=3.5.8-r0' \
        'libssl3>=3.5.8-r0'
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist /usr/share/nginx/html
USER 101:101
EXPOSE 8080
