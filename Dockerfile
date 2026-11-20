# syntax=docker/dockerfile:1
FROM golang:1.27 AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /xhr-exit ./cmd/xhr-exit

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /xhr-exit /xhr-exit
EXPOSE 8080
USER nonroot:nonroot
ENTRYPOINT ["/xhr-exit"]
