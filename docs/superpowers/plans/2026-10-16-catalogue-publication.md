# Catalogue Publication Implementation Plan

> **For agentic workers:** Execute inline against the approved design using red/green slices; review the resulting diff before completion.

**Goal:** Retain a published catalogue's encoding across later publications and remove callback pairing from composition.

**Architecture:** A concrete read-only list carries precomputed JSON and bytes. ShopCodecs recognizes it; ShopWindow atomically publishes it with domain state.

**Tech Stack:** Kotlin, Pelican codecs and typed endpoints, JUnit/Kotest, Gradle.

## Task 1: Retain the captured publication

**Files:**
- Test: `app/src/test/kotlin/petshop/app/CatalogueBytesSpec.kt`
- Create: `api/src/main/kotlin/petshop/api/Catalogue.kt`
- Modify: `api/src/main/kotlin/petshop/api/Codecs.kt`
- Modify: `app/src/main/kotlin/petshop/app/Adoptions.kt`
- Modify: `app/src/main/kotlin/petshop/app/Wiring.kt`
- Adapt: `api/src/test/kotlin/petshop/api/CatalogueCodecSpec.kt`

- [x] Add a test capturing the original list and `codec.encoded(list)`, publishing another pet, then asserting `codec.encoded(original)` is the same original byte array.
- [x] Run `./gradlew --offline :app:test --tests petshop.app.CatalogueBytesSpec --console=plain`; observe the retained-byte assertion fail.
- [x] Introduce `Catalogue(pets: List<PetDto>) : AbstractList<PetDto>(), RandomAccess` with a defensive snapshot, cached JSON and bytes, and structural list behavior inherited from AbstractList.
- [x] Teach both ShopCodecs codec overloads to recognize the declared `List<PetDto>` type, return stored encoding for Catalogue values, and retain plain-list decoding/encoding fallback.
- [x] Build Catalogue in ShopWindow.show and the empty Shelf; remove separate JSON/byte fields and methods, remove shopCodecs callback factory and its production wiring.
- [x] Adapt existing catalogue tests to the publication-owned encoding. Preserve isolation, copied-list fallback, new-byte-array and serving assertions.
- [x] Run `./gradlew --offline :api:test :app:test --tests '*Catalogue*' --console=plain` and verify the regression passes.

## Task 2: Protect publication semantics and verify

- [x] Test that mutable constructor inputs cannot change the published list or its encoding, observing a failure before adding any missing copy.
- [x] Check normal structural list equality, hash code, iteration and indexing through existing typed endpoint/codec tests.
- [x] Run `./gradlew --offline check --console=plain` and the existing browsing load test if Docker is available.
- [x] Review the scoped diff against the design and repository instructions; resolve actionable findings.
- [x] Record verification results and report the resulting changes without including unrelated working-tree edits.
