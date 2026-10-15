# Upstream community images were removed. Build the pinned source release that includes the October 2025 security fix.
FROM golang:1.25.1-alpine@sha256:b6ed3fd0452c0e9bcdef5597f29cc1418f61672e9d3a2f55bf02e7222c014abd AS build
RUN apk add --no-cache git ca-certificates
RUN CGO_ENABLED=0 GOBIN=/out go install github.com/minio/minio@RELEASE.2025-10-15T17-29-55Z
FROM alpine:3.22@sha256:5291449c3df73caf6ed85e649dec1b9e818b39a5d8c871e97afc13e9cd5e8fa8
RUN apk add --no-cache ca-certificates && addgroup -g 1001 app && adduser -D -u 1001 -G app app && mkdir /data && chown app:app /data
COPY --from=build /out/minio /usr/local/bin/minio
USER app
EXPOSE 9000 9001
ENTRYPOINT ["/usr/local/bin/minio"]
CMD ["server","/data","--console-address",":9001"]
