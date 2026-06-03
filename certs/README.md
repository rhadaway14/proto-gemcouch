# Test certificates

These are **self-signed, test-only** TLS materials used by the TLS integration test and the
`protogemcouch-tls` Docker Compose service. They are **not** for production use.

- `server-keystore.p12` — server key + self-signed cert (`CN=localhost`, SAN includes `localhost`,
  `127.0.0.1`, `protogemcouch-tls`). Password: `changeit`.
- `client-truststore.p12` — trusts the server cert (for the Geode SSL client). Password: `changeit`.
- `server-cert.pem` — exported server certificate.

Regenerate with:

```bash
keytool -genkeypair -alias protogemcouch -keyalg RSA -keysize 2048 -validity 3650 \
  -dname "CN=localhost, OU=ProtoGemCouch, O=Test, L=Test, ST=Test, C=US" \
  -keystore server-keystore.p12 -storetype PKCS12 -storepass changeit -keypass changeit \
  -ext "SAN=dns:localhost,dns:protogemcouch-tls,ip:127.0.0.1"
keytool -exportcert -alias protogemcouch -keystore server-keystore.p12 -storepass changeit -rfc -file server-cert.pem
keytool -importcert -alias protogemcouch -file server-cert.pem -keystore client-truststore.p12 \
  -storetype PKCS12 -storepass changeit -noprompt
```

For production, supply your own keystore via `TLS_KEYSTORE_PATH` and friends; never reuse these.
