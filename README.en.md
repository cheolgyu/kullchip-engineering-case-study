# KullChip Engineering Case Study

KullChip was an Android/Wear R&D project for durable walk recording, relative navigation, and pet-behavior inference. This repository is a **sanitized engineering case study**, not the complete product source or a production release.

The project combined Kotlin/Compose/Room on Android and Wear OS, a Rust gRPC/PostgreSQL backend, and Python validation tooling. Its most important outcome was not a successful ML claim: field evidence showed that the automatic PDR/ML stack did not meet product gates, so uncertified outputs were kept out of the product and the project was sealed at version 8.1.0.

## What this repository demonstrates

- durable RAW-first ingestion from Phone/Wear sensors;
- bounded spool, ACK, retry, and session fencing across disconnections;
- a single serialized runtime owner and Room-backed projections;
- product/validation/Model Lab authority separation;
- walk-level holdout evaluation and baseline comparison;
- fail-closed model promotion based on artifact identity and measured value;
- a dependency-free, runnable Rust reconstruction of bounded delta-sync policy;
- an explicit decision to stop when observability, data, and stability were insufficient.

See the [Korean README](README.md), [architecture](docs/02-architecture.md), [validation results](docs/03-validation.md), [bounded-sync example](examples/bounded-sync/README.md), and [claim boundaries](docs/06-claims-boundary.md).
