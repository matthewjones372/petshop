# Working in this repo

## Skill workflows

When selecting or switching skills, read [workflow selection](docs/agents/workflow.md).

## Comments

Comments record what the code cannot: the reason a thing is done the way it is.
They do not restate the code, and they are not essays.

**Write a comment when there is a why.** A constraint from a dependency (a
version pin and the advisory behind it, Prometheus's one label set per metric
name), a guarantee the code relies on (at-least-once, the order a graph releases
nodes in), a value chosen for a reason (a timeout, a port that avoids a clash),
a bug a naive version reintroduces. One or two sentences.

**Do not write one when there is not.** If the name, the signature and the body
already say it, say nothing.

### Rules

- **KDoc: one line by default.** Two or three only where the reason genuinely
  takes them. A multi-paragraph block is for a decision the reader would
  otherwise undo, and there should be very few.
- **No restating the code.** `/** Every pet in the shop. */ fun all()` earns
  nothing, and neither does `// Closed after the relay stops` above a release
  that says so.
- **No history.** "This used to…", "before spec…", "the alternative was…"
  belongs in the commit message, not the source.
- **No rhetorical framing or stories.** State the fact.
- **Inline `//` notes are for the line below them.** Past three lines it is
  either KDoc or too long.
- **Config files too.** A comment in `application.conf`, a compose file or a
  build script says which variable overrides a setting or why a value is pinned,
  in one line.
- **Tests too.** The test name carries the claim; a KDoc above it does not
  repeat the name in longer words.

### Shape

```kotlin
// Yes: names a real constraint, once.
// boundTo: getTracer is Java, so the inferred key is the platform type Tracer! that nothing matches.
single { sdk: OpenTelemetrySdk -> sdk.getTracer("petshop") }.boundTo<Tracer>()

// No: restates the code, then argues for it.
/**
 * The typed view of the same system, which is the one Pelican binds a port with. We take it
 * here rather than ...
 */
```

## No `runCatching`

Never, in main or test sources. It catches interrupts, cancellations and bugs
along with the failure that was meant. Catch the exception you expect, by name,
or let it propagate.

## Use what lark and pelican already provide

Before writing a scheduler, a retry, a breaker, a health check, a config reader
or a test wait, look for it in [lark](https://github.com/matthewjones372/lark)
(its `docs/cookbook.md` ends with a need-to-API table) and
[pelican](https://github.com/matthewjones372/pelican) (`docs/modules.md`). Background work
is a lark `Stream` on the graph's `StreamBackend`, settings are read with
lark-app-typesafe's `config`/`secret`, readiness is pelican-health, and a test
waits with lark-test's `eventually`, never `Thread.sleep`.
