# ProtoGemCouch Test Strategy

## Purpose

This document describes the testing approach for ProtoGemCouch, including unit tests, integration tests, protocol discovery, regression protection, and performance testing.

---

## 1. Test goals

The goals of the test strategy are:

- prove correctness of supported operations
- catch protocol regressions early
- validate end-to-end client compatibility for scoped flows
- document semantic gaps explicitly
- build confidence for launch readiness

---

## 2. Test pyramid

ProtoGemCouch uses a layered strategy:

### Unit tests
Fast tests for:
- utilities
- factories
- registry construction
- handler behavior with mocked inputs
- response writer behavior

Command:

```powershell
mvn test