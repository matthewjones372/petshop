# Catalogue publication

Approved design from the catalogue architecture discussion, 16 October 2026.

## Goal

Keep each published pet listing and its cached JSON and UTF-8 bytes together, so an in-flight response retains its selected publication after the shop publishes an update.

## Design

A concrete read-only list in the api module owns a snapshot of pet DTOs and their precomputed encoding. It retains ordinary structural list behavior. The declared endpoint type remains `List<PetDto>`, preserving the JSON array, schema and typed clients. ShopCodecs recognizes this list and returns its stored string or bytes; ordinary lists use the existing serializer.

ShopWindow publishes the catalogue alongside domain pets in its existing atomic Shelf swap after a durable write. Wiring supplies only the listing reader. Remove the separate JSON and byte readers and the per-shop callback codec. Domain reads, sorting, actor writes, transactions, errors and lifecycle behavior remain unchanged. No Pelican changes, encoding history or thread-local state are needed.

## Acceptance

- Capture catalogue A, publish B, then encode A: return A's original byte array and JSON.
- Encoding B returns B's encoding, with a distinct array.
- Multiple shops retain their own publication encodings.
- Ordinary copied lists encode normally and offer no cached bytes.
- A catalogue behaves like an ordinary list for equality, iteration, indexing and serialization; mutation of a constructor input cannot change a publication.
- Existing contract, golden, blocked-write, restart and serving tests remain valid.

## Verification

Start with the retained-byte regression in CatalogueBytesSpec. Run focused catalogue tests, then the normal check suite and existing browsing load test when its Docker dependencies are available. Record unavailable checks explicitly. Byte-array identity proves reuse; throughput improvement is not a claim of this refactor.

## Verification results

- Retained-byte regression failed against the callback codec before the implementation.
- Mutable-input regression failed before copying the publication's constructor input.
- `./gradlew --offline check --console=plain`: passed; 13 api tests and 66 app tests, with no failures or skips.
- `./gradlew --offline :loadtest:loadTest --tests '*PetshopLoadTest.browsing holds up*' --console=plain`: passed the existing browsing test at 200 requests per second.
- A fresh read-only reviewer found no actionable issues against the approved design or repository standards.
