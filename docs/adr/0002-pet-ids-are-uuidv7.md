# 2. Pet ids are UUIDv7

Date: 2026-10-21

## Status

Accepted

## Context

`PetId` wraps a `Long`. `Arrivals` mints ids from an `AtomicLong` seeded with the highest id the instance can see
when it starts. The shop runs as more than one instance on one `pets` table (the load test runs two), and two
instances started from the same maximum hand out the same next ids, which collide on `pets.id PRIMARY KEY`. The load
test hides this only because it sets `petshop.arrivalsEvery = 1h`, so no pet arrives during it.

So the requirement is minting ids on several instances without coordination. Ids that cannot be guessed are welcome
but not required: `GET /pets` lists every id anyway.

Ids must not be minted by the database. The shop's domain owns its ids, and minting them should not need a round trip
to storage.

Nothing is deployed, so compatibility is not a constraint: the Avro schema, the changelog, the API and the web client
change in place, with no new field, no second schema and no backfill.

## Decision

A pet's id is a UUIDv7 (RFC 9562), held as `@JvmInline value class PetId(val value: java.util.UUID)`.

- **Minting.** Ids are minted in the process with `kotlin.uuid.Uuid.generateV7().toJavaUuid()`, in one function
  carrying `@OptIn(ExperimentalUuidApi::class)`, since `generateV7` is still experimental in Kotlin 2.4.20 and the
  opt-in cannot go in build configuration.
- **Ordering.** Pets are listed in id order, and id order is arrival order. Within an instance the order is exact,
  because `generateV7` is monotonic. Across instances it holds to the millisecond: two arrivals in the same
  millisecond on different instances may list in either order. `UUID.compareTo` (signed halves), Postgres `uuid`
  ordering (bytes) and the canonical lowercase string all agree for v7 ids, and a test pins that they do.
- **Every id is a v7.** The opening pets (Nibbles, Barnaby, Mrs Peel) get fixed v7 literals timestamped before any
  arrival, so they sort first and `shelf(opening)` finds them again after a restart. A non-v7 id would break the
  ordering guarantee.
- **Arrivals.** Name and species are drawn with `kotlin.random.Random` instead of `id % n`. The
  `petshop.pets.in.shop` gauge is set from `shop.all().size` instead of the latest id.
- **Edges.** Postgres stores `UUID` columns. Avro carries a `string` with the `uuid` logical type. The API and the
  chip registry take a UUID path param, so a malformed id is a 400 rather than a 404. JSON and the web client carry
  the id as a string. Errors carry the `PetId` rather than a `Long`.

## Alternatives considered

- **A Postgres sequence** keeps `Long`, 8-byte keys and short URLs, and needs one `nextval` per arrival. Rejected
  because it ties minting to the database.
- **A Snowflake-style long** (time, instance bits, counter) stays a `Long` with no database, but each instance needs a
  unique instance id from configuration or a lease. Two instances given the same one collide silently, which is the
  current bug in a new place.
- **A random 63-bit long** needs no coordination and keeps `id % n`, but is not time-ordered, is a home-made scheme,
  and reaches even odds of a collision at around three billion pets.
- **Wrapping `kotlin.uuid.Uuid`** would avoid converting at the minter and has a kotlinx-serialization serializer
  built in. `java.util.UUID` was preferred because pelican's path-param codec and JDBC take it directly.

## Consequences

- Concurrent instances can mint ids with no coordination, which fixes the collision.
- Ids are effectively unguessable (74 random bits), so ids cannot be enumerated by counting.
- Keys are 16 bytes instead of 8, in `pets.id`, `outbox.pet_id` and their indexes. v7's time prefix keeps index
  inserts at the right-hand edge, as a counter did.
- URLs grow from `/pets/3` to `/pets/0190f5a8-...`, 36 characters for the id.
- An id reveals the millisecond the pet arrived. That is public information for a shop, so it is accepted.
- The change touches every layer at once: domain, app, changelog, Avro, API, registry, web and load test. The golden
  OpenAPI and Avro files change on purpose and go up for review.
- pelican-openapi does not emit `format: uuid`, so the published spec shows the id as a plain `string` until pelican
  supports it.
- Ordering across instances is only as good as their clocks agree, to the millisecond.
