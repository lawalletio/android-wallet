# LaWallet for Android — Privacy Policy

_Last updated: October 20, 2026_

LaWallet is an open-source Lightning wallet client (MIT license, source at
https://github.com/lawalletio/android-wallet). This policy explains what the app handles and where it goes.

## What stays on your device
- **Your Nostr secret key** (if you create one or import an nsec), encrypted with a non-exportable Android
  Keystore key. It is never sent to any server and is excluded from backups and device transfers.
- **Preferences and caches**: the instance you use, your display currencies, language, payment sound, your
  recent activity, and recently used Lightning addresses.

## What the app sends, and to whom
- **Your LaWallet instance** (by default `beta.lawallet.io`; you can change it in Settings → Instance):
  - your Nostr public key and signed authentication events, used to sign in;
  - account actions you request, such as claiming a Lightning address, managing cards and remote wallets, and currency
    preferences.

  The instance operator's own privacy policy applies to data they hold.
- **Your wallet's Nostr relay (NWC)**: encrypted wallet requests (balance, invoices, payments, transaction history)
  go directly to the wallet you connected. The LaWallet server is not in the payment path.
- **Lightning services you choose to interact with**: when you pay a Lightning address, LNURL or invoice, or claim a
  voucher, the app talks directly to that service.
- **Exchange rates**: if you enable fiat currencies, rates are fetched from `api.yadio.io` (no account data is sent).
- **Images**: community logos, card designs and avatars are loaded from the URLs the instance provides.

The app contains **no analytics, advertising or tracking SDKs**, and does not sell or share personal data.

## Permissions
- **Camera**: only to scan QR codes, while the scanner is open. Frames are processed on the device and never stored.
- **NFC**: to read Bolt card / LNURL-withdraw tags when you tap a card.
- **Notifications and foreground service**: to tell you about incoming and outgoing payments in real time. You can turn
  this off in Settings.
- **Biometrics / device lock**: to protect revealing your secret key.

## Passkeys
If you sign in with a passkey, your key is derived on the device from the passkey (WebAuthn PRF). Neither LaWallet nor
the instance receives it.

## Deleting your data
Settings → Remove wallet erases everything stored by the app on your device. Data held by your instance or wallet
provider is governed by them; contact your community operator.

## Account deletion
To delete your LaWallet account on the default instance (`beta.lawallet.io`, run by La Crypta):

1. Open the app, go to Settings → Security and copy your public key (`npub…`).
2. Email info@lacrypta.ar with the subject "Delete my LaWallet account" and include that `npub`.
3. We may ask you to confirm with a message signed by that key, to prove the account is yours.

We delete your account record and linked public keys, Lightning addresses, card links, remote wallet connections,
passkeys and preferences within 30 days of confirming the request, and reply when it's done. Lightning payments
already made can't be reversed and stay in the records of the wallets and nodes that handled them. Funds held in a
connected wallet stay with that wallet's provider: move them out before deleting. For other instances, contact that
community's operator.

## Contact
info@lacrypta.ar — or open an issue at https://github.com/lawalletio/android-wallet/issues
