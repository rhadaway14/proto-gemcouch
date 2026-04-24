# ProtoGemCouch Release Checklist

## Purpose

This checklist is used before cutting a release tag, sharing a build, or declaring a deployment candidate ready for scoped use.

---

## 1. Source and build

- [ ] Working tree is clean
- [ ] Branch is correct
- [ ] `mvn clean test` passes
- [ ] `mvn verify` passes
- [ ] `mvn clean package` produces the runnable jar
- [ ] Docker image builds successfully

Commands:

```bash
mvn clean test
mvn verify
mvn clean package
docker build -t protogemcouch:release-candidate .