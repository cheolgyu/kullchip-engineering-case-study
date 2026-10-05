# Bounded Sync (sanitized reconstruction)

This is a small public reconstruction of synchronization policies explored in
KullChip. It is **not copied production source** and contains no production
schema, endpoint, credential, user record, location, or proprietary dataset.
All identifiers in tests and examples are synthetic.

The dependency-free Rust crate demonstrates four ideas:

- cap event pages at 500 items;
- compare independent profile, activity, and live version lanes;
- choose delta sync only while a cursor remains inside retained history;
- keep device acknowledgements monotonic and cap them at accessible history.

The crate deliberately stops at the policy boundary. A real adapter would make
the cursor update atomic in its database and authorize the newest accessible
event before acknowledging it.

## Run

```sh
cargo test --workspace
cargo run -p bounded-sync-core --example demo
```
