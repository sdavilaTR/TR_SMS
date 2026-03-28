# Configuración de firma APK (Release Signing)

## Keystore

| Campo | Valor |
|-------|-------|
| Formato | PKCS12 |
| Archivo local | `atlas-release.jks` (en `.gitignore`, nunca se commitea) |
| Alias | `atlas-release-key` |
| Validez | 10.000 días (~2053) |
| Algoritmo | RSA 2048 / SHA256withRSA |

> **Importante**: el archivo `.jks` local está en `.gitignore`. El CI lo reconstruye en cada build a partir del secret `KEYSTORE_BASE64`.

## GitHub Actions Secrets

Configurados en `sdavilaTR/HassiSiteApp` → Settings → Secrets → Actions:

| Secret | Descripción |
|--------|-------------|
| `KEYSTORE_BASE64` | Keystore PKCS12 codificado en base64 |
| `KEYSTORE_PASSWORD` | Contraseña del keystore |
| `KEY_ALIAS` | `atlas-release-key` |
| `KEY_PASSWORD` | Contraseña de la clave |

## Regenerar el keystore (si se pierde)

```bash
# 1. Generar nuevo keystore PKCS12 (IMPORTANTE: usar PKCS12, no JKS)
keytool -genkeypair \
  -keystore atlas-release.jks \
  -storetype PKCS12 \
  -alias atlas-release-key \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass 'Atlas.Dev.2026!' \
  -keypass 'Atlas.Dev.2026!' \
  -dname "CN=ATLAS Dev, OU=Development, O=ATLAS Solutions, L=Madrid, ST=Madrid, C=ES"

# 2. Subir los secrets a GitHub
gh secret set KEYSTORE_BASE64 --repo sdavilaTR/HassiSiteApp < <(base64 -w 0 atlas-release.jks)
gh secret set KEYSTORE_PASSWORD --repo sdavilaTR/HassiSiteApp --body 'Atlas.Dev.2026!'
gh secret set KEY_ALIAS --repo sdavilaTR/HassiSiteApp --body 'atlas-release-key'
gh secret set KEY_PASSWORD --repo sdavilaTR/HassiSiteApp --body 'Atlas.Dev.2026!'
```

## Flujo de release

1. Push a `main` → GitHub Actions lanza el workflow `release.yml`
2. El workflow decodifica `KEYSTORE_BASE64` → `atlas-release.jks`
3. Gradle firma el APK con el keystore
4. Se publica un release con tag `vYYYY-MM-DD-HH-MM`
5. La app detecta el nuevo release en el próximo arranque y ofrece actualizar

## Notas

- Usar siempre **PKCS12**, no JKS. JKS generado con Java 9+ es incompatible con las Android build tools (`Tag number over 30 is not supported`).
- Las contraseñas actuales son de desarrollo. Cambiarlas antes de producción real.
