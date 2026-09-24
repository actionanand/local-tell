# GitHub setup

Recommended repository name: `actionanand/localtell`.

## Branch

The Android workflow intentionally runs only on `main-android`.

If starting from this ZIP locally:

```bash
git init -b main-android
git add .
git commit -m "feat: initial LocalTell Android app"
git remote add origin https://github.com/actionanand/localtell.git
git push -u origin main-android
```

If the GitHub repository already has another default branch, create `main-android`, upload/commit these files there, and optionally make it the default branch.

## Signing secrets

Repository **Settings → Secrets and variables → Actions**:

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

The included keystore generator uses alias `localtell`. For the PKCS12 file it creates, `KEY_PASSWORD` should normally be the same as `KEYSTORE_PASSWORD`.

The workflow decodes the keystore only on the GitHub runner, signs and verifies APK/AAB, then deletes the decoded file in an `always()` cleanup step.

## Actions permissions

Because the workflow automatically increments `android-version.json` and commits release files, repository Actions must be allowed to write repository contents. The workflow already declares:

```yaml
permissions:
  contents: write
```

Branch protection must also allow the GitHub Actions bot to push these generated `[skip ci]` commits, or the auto-version/release commit steps will fail.
