# An adoption is confirmed by the chip registry

An adoption records the pet as being adopted before the chip registry is asked, and becomes an adoption only when the registry confirms the new keeper. The registry is the source of truth: whatever it says about the keeper is what the shop records. Only confirmed adoptions are published, so consumers never hear an adoption that is later undone, and the shop writes no returned event. The schema keeps its PetReturned record, read but never written, so the topic's history and an outbox left unpublished across the upgrade stay readable.

When the shop cannot finish an adoption within the request (the registry's answer is unknown, or a write fails), the adoption is settled later by asking the registry again, with backoff, until the outcome is known and recorded. Settling starts from the request that could not finish, and at start-up for every pet still being adopted, since nothing is in flight then.

## Considered options

- **Reserve in memory, transfer, then record**: drops the being-adopted write, but a crash or failed write after a successful transfer leaves the registry naming a keeper the shop has no record of.
- **Record adopted, publish, and publish a return on refusal**: consumers hear adoptions that never happened, and a failed return leaves the pet adopted with no recovery.
- **A periodic sweep over pets being adopted**: needs a timestamp and a threshold to tell abandoned adoptions from ones in flight. Settling knows which they are from where it starts, so it needs neither.
