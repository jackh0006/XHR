# Contributing

XHR is a protocol project. A change that breaks the wire format breaks every
client, so keep changes small and tested.

## Before you start

* Read [docs/PROTOCOL.md](docs/PROTOCOL.md) and [SECURITY.md](SECURITY.md).
* One fix or feature per pull request.
* Never commit real keys, deployment IDs, `config.json`, or `.dev.vars`.

## Checks

```bash
gofmt -l .
go vet ./...
go test -race ./...
go build ./...

cd cloudflare
node --test frames.test.mjs
node --check worker.js
```

CI runs the same checks. The GAS file is syntax-checked by copying it to a
temporary `.js` file and running `node --check`.

## Style

* Go: `gofmt`, small functions, explicit errors.
* JavaScript: ES modules, no dependencies in the Worker beyond
  `cloudflare:sockets`.
* Protocol changes must update `docs/PROTOCOL.md` and both exits together.
* User-facing text: short, simple, American English.

## Commit messages

```text
type: short summary
```

Types: `feat`, `fix`, `docs`, `test`, `security`, `refactor`, `ci`.

## Pull request checklist

- [ ] `gofmt -l .` is empty
- [ ] `go vet ./...` is clean
- [ ] `go test -race ./...` passes
- [ ] `go build ./...` succeeds
- [ ] Node frame tests pass
- [ ] Worker and GAS syntax checks pass
- [ ] No secret or personal deployment value is committed
- [ ] Protocol docs updated when the frame/request format changes

## Security issues

Do not open a public issue. Use **Security → Report a vulnerability**.

## License

Contributions are licensed under MIT.
