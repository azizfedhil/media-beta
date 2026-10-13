# Code signing for Pear on Windows

**Status: not signed yet.** Until a certificate is set up, the installer and the app are unsigned. Windows shows a
SmartScreen warning on first run. Users click **More info**, then **Run anyway** (see the end of this page).

The CI hook is already in place. It signs the app's `.exe` and `.dll` files and `PearSetup.exe` when the secret
`WINDOWS_SIGN_PFX_BASE64` is set, and skips signing when it isn't. The signing step is `tools/sign_windows.sh`.

Facts below were checked on 2026-10-13 against Microsoft's Q&A pages, the vendors' own pages, and recent third-party
guides. Prices change and sources disagree, so **re-check the eligibility page and the price at checkout** before buying.

## The options

### 1. Artifact Signing (formerly Azure Trusted Signing), a Microsoft service

Renamed from "Trusted Signing" in January 2026. It is a managed cloud signer: no certificate file and no USB token.

- **Eligibility (Public Trust, which Windows trusts):** organizations incorporated in the USA, Canada, the European Union
  and the United Kingdom. **Individual developers only in the USA and Canada.** Microsoft's own Q&A page states this
  (the page was current on 2026-06-09).
- **Consequence for France:** a company incorporated in France (SAS, SARL and similar) should be eligible as an
  organization. A **sole trader or individual developer in France is not eligible**, because individuals are limited to
  the USA and Canada. Check that your legal entity type is accepted in the identity validation step before you pay.
- Older sources (April 2025) describe a US/Canada-only, three-years-of-history restriction. Later sources show it widened.
  Trust the validation screen in the Azure portal over any article.
- Cost: a few US dollars a month, per one third-party guide (about $10/month). Check the Azure pricing page.

### 2. SignPath Foundation, free for open-source projects

- Free signing for qualifying open-source projects. The certificate is issued to the SignPath Foundation, and the
  signature names the project and its source repository.
- **Conditions (from signpath.org):** the project must be open source under an OSI-approved licence, and its source must
  be in a public repository. It may contain **no proprietary code**. The people who sign must be the people who maintain
  the code, and they must use multi-factor authentication. Only automated CI builds may be signed.
- **This only works if the repository is public under an OSI-approved licence.** The repository has no LICENSE file
  yet, so this option is closed for now. If Pear is published as open source, this becomes the free route.

### 3. An OV code-signing certificate from a certificate authority

- Since **1 June 2023**, an OV or EV code-signing key must live on a hardware token, in a hardware security module (HSM),
  or in a CA's cloud HSM. A plain `.pfx` file is no longer issued. The CI script takes a `.pfx`, so with a token or cloud
  HSM the signing step must call the CA's signing tool instead (see "Wiring a cloud signer" below).
- Since **1 March 2026**, a publicly trusted code-signing certificate lasts at most about **15 months** (the sources say
  458 to 460 days). Plan a renewal every year.
- Prices from third-party comparisons (May–June 2026), before tax and token or cloud fees:
  - Sectigo OV, roughly $219 to $322 a year through resellers.
  - DigiCert OV, roughly $400 to $439 a year, plus cloud or token fees.
  - Certum cloud OV, about $116 a year through a reseller, per one comparison.
  Prices differ a lot by reseller. Get quotes from two sellers.
- **Individual developers:** an *individual* validation (IV) certificate exists (SSL.com lists one). It is issued to a
  named person rather than a company, so it fits a sole trader in France if a CA will issue to them. Check the CA's
  eligibility for your country before buying.

### 4. An EV certificate

Not needed for Pear. EV is required for kernel-mode drivers, and it costs more than OV.

## Recommendation

1. **If Pear's repository becomes public under an OSI licence:** SignPath Foundation. It costs nothing and needs no
   personal identity papers.
2. **If a French company will publish Pear:** Artifact Signing, once the company's identity validation passes.
3. **If a single developer publishes Pear:** an IV or OV certificate from a CA that offers cloud-HSM signing (no USB
   token to post), signed through the CA's cloud signer in CI.

Whichever route is chosen, the signature only proves who signed the file. A new publisher still gets SmartScreen
warnings until people download the installer for a while. That is normal.

## Wiring a signer into CI

`tools/sign_windows.sh` signs with `signtool` and a `.pfx` file. It takes its settings from the environment:

| Secret or variable | Purpose |
| --- | --- |
| `WINDOWS_SIGN_PFX_BASE64` | The certificate as base64 (`base64 -w0 cert.pfx`). Set this to turn signing on. |
| `WINDOWS_SIGN_PFX_PASSWORD` | The password of that `.pfx`. |
| `WINDOWS_SIGN_TIMESTAMP_URL` | Optional. A timestamp server, so the signature stays valid after the certificate expires. The default is DigiCert's. |

For a cloud signer (Artifact Signing, SignPath, or a CA's cloud HSM), replace the `signtool sign ... /f` call with that
provider's client, which the provider documents for GitHub Actions. Keep the same placement: after
`:desktopApp:createDistributable` (for the app files) and after the Inno Setup build (for `PearSetup.exe`).

Check a signed file on Windows with:

```powershell
Get-AuthenticodeSignature .\PearSetup.exe | Format-List Status, SignerCertificate, TimeStamperCertificate
```

## Until the installer is signed

Users see a SmartScreen warning the first time they run `PearSetup.exe`:

1. Click **More info** in the "Windows protected your PC" dialog.
2. Click **Run anyway**.

Tell users this in the release notes until signing is in place. Do not tell them to turn SmartScreen off.
