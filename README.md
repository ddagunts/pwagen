<div align="center">

<img src="docs/icon.png" alt="" width="120">

# pwagen

**Turn a website into a real Android app: one package, one UID, its own firewall identity.**

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%2011%2B-3ddc84.svg)](#requirements)
[![Generator network access](https://img.shields.io/badge/generator%20network%20access-none-success.svg)](#the-generator-has-no-network-access)

</div>

---

## Why this exists

Android gives every installed APK one UID, and per-app firewalls key their rules to
that UID. So a single app holding ten web apps has **one** network identity. A
self-hosted dashboard cannot reach the LAN while a chat client stays confined to
Wi-Fi, because to the firewall they are the same app.

pwagen takes the other route. Each web app you add is built into its own signed,
installable APK. That gives every site:

- **Its own UID.** Any per-app firewall controls it independently, with no integration
  and nothing to configure in pwagen.
- **Its own data sandbox.** Cookies, storage and service workers isolated by the
  kernel, not by in-process bookkeeping.
- **Its own permissions.** Camera, microphone and location granted and revoked per
  site in Android Settings, like any other app.
- **Its own launcher icon, name and recents entry**, because it *is* its own app.

## Screenshots

<div align="center">

<table>
<tr>
<td width="33%"><img src="docs/screenshots/app-list.png" alt="The generator's app list, showing three web apps with their derived package names"></td>
<td width="33%"><img src="docs/screenshots/editor.png" alt="Editing a web app: site URL, launcher name, and the package name derived from the host"></td>
<td width="33%"><img src="docs/screenshots/transport-security.png" alt="Transport security switches: plaintext HTTP, invalid certificates, and CA trust"></td>
</tr>
<tr>
<td align="center"><b>Your web apps</b><br><sub>Each row is one package that will be installed separately.</sub></td>
<td align="center"><b>Adding one</b><br><sub>The package name is derived from the host, so it is reproducible.</sub></td>
<td align="center"><b>Transport security</b><br><sub>Hardened by default. Each relaxation says what it costs.</sub></td>
</tr>
</table>

</div>

## Install

<div align="center">

[<img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="60">](https://apps.obtainium.imranr.dev/redirect.html?r=obtainium://app/%7B%22id%22%3A%22dev.pwagen.app%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fddagunts%2Fpwagen%22%2C%22author%22%3A%22ddagunts%22%2C%22name%22%3A%22pwagen%22%7D)

</div>

Or add it manually in Obtainium with the URL `https://github.com/ddagunts/pwagen`,
or grab an APK from [Releases](https://github.com/ddagunts/pwagen/releases).

> [!IMPORTANT]
> **Pick one install source and stay on it.** pwagen's signing key lives in your
> device's hardware keystore and is tied to the installed app. Switching source later
> (say, a GitHub build to an F-Droid build) is an uninstall plus install, not an
> update. That destroys the key and forces every generated app to be reinstalled.
> Ordinary updates from the same source are safe.

## How it works

pwagen ships a tiny WebView shell as a template APK. For each web app you add it:

1. Rewrites the template's package name, label and version.
2. Adds **only** the permissions you opted into. Everything else is absent.
3. Replaces the launcher icon at every density.
4. Embeds your settings as `assets/config.json`.
5. Signs the result with a hardware-backed key and hands it to the system installer.

`classes.dex` is byte-identical in every generated APK: the template names its
activity by fully-qualified name, so the code does not depend on the package it gets
rewritten to. Only the manifest, the icons, one asset and the signature ever differ.

## What a generated app does

Each generated app is one chromeless WebView pointed at one site, and nothing else. It
is the network-facing half of the project, so it is deliberately tiny. The shared
config schema is its only dependency. Compose, AppCompat and analytics libraries are
all absent.

**Hardened by default.** Each relaxation is opt-in per site and stays revocable
afterwards in Android Settings:

| Setting | Default | Why |
|---|---|---|
| Safe Browsing | **off** | It reports visited URLs to Google |
| Third-party cookies | **off** | Cross-site tracking |
| File & content URL access | **off** | Reduces reachable local surface |
| Camera / microphone / location | **off** | Permission is absent from the manifest unless opted in |
| Mixed content | **blocked** | No silent downgrade to HTTP |
| JavaScript & DOM storage | on | Required for anything to work |

**Scope.** Links that leave the site open in your normal browser instead of loading
inside the app, so unrelated browsing never runs under that app's UID.

**Pull to refresh.** Dragging down with the page already at its top reloads it. The
gesture is only claimed while the page has nowhere left to scroll. It is abandoned as
soon as the page moves or a second finger lands, so it cannot swallow a swipe the site
wanted.

**Full screen.** On by default: the system bars are hidden and the site fills the
display, with a swipe from the top edge bringing the status bar back. Turn it off and
the bars stay put with the page held below them, tinted with the site's theme colour.
What is never on offer is a page drawn under a *visible* status bar: it would have a
band across its top that looks live but sends every tap to the system instead.

*Keep the status bar* is full screen with the clock, battery and signal left on
screen — the navigation bar still goes, and the page is held below the status bar
rather than under it. Worth it for an app you watch for a while, where losing the time
costs more than the strip is worth.

Hiding the bars does not move the camera, so a full-screen app still stops below the
display cutout and fills that strip with the theme colour. *Draw under the camera
cutout* gives the strip back to the page. That is worth it for a site whose top edge is
empty, and not otherwise: unlike a status bar, no swipe reveals what the lens covers.
It does not apply when the status bar is kept, since that bar already covers the lens.

## Domain rules

Each site can carry its own allowlist or blocklist, applied to **every** request it
makes: scripts, images, XHR and fetch, not just navigation. Blocked requests get a
real 403, so failures surface in the page's own error handling rather than silently
appearing as empty content.

| Pattern | Matches | Does not match |
|---|---|---|
| `example.com` | `example.com` | `cdn.example.com` |
| `*.example.com` | `cdn.example.com`, `a.b.example.com` | `example.com` |
| `ads.*.example.com` | `ads.eu.example.com` | `cdn.eu.example.com` |
| `*` | any host | nothing |

`*` is the only metacharacter. Everything else is literal, so a dot is a dot. To cover
a domain *and* its subdomains, list both forms. pwagen seeds that pair when you first
switch a site to allowlist mode.

There are deliberately **no implicit rules**. An empty allowlist blocks the site's own
requests too. What you see in the config is what is enforced, with no hidden
exceptions to reason around.

**Seeing what got blocked.** A rule that is working refuses things constantly, so by
default refusals go only to logcat. Turn on *Announce blocked requests* while tuning a
list and the app names each refused host the first time it comes up. A **Mute** button
silences that host for good, so finding what you forgot to allow does not cost you a
page that complains forever. Muting is remembered inside the generated app. Clearing
its storage in Android Settings resets it.

This sits *underneath* whatever per-app firewall you use rather than replacing it. A
firewall sees the app's UID and can stop it reaching a host at all. These rules work
inside the app, where the origin of each individual request is still known.

### Start from an example

The Add screen offers ready-made definitions that fill the whole form, editable
afterwards like anything you typed yourself. **Wikipedia** is the one that ships:

| | |
|---|---|
| Site | `https://en.m.wikipedia.org/` — the mobile host, so the first load is not redirected out of its own scope |
| Permissions | none: no camera, microphone, location or notifications, so none appear in the generated manifest |
| Safe Browsing | off, so no visited URL is reported to Google |
| Domain rules | allowlist of `wikipedia.org`, `*.wikipedia.org`, `wikimedia.org`, `*.wikimedia.org` — article text comes from the first pair, every image and script from the second |
| Announce blocked requests | on, so you can watch the allowlist work |

Nothing else is reachable from it. For a reference app that is the point: there is no
login to keep and no upload to make, so an outbound request anywhere else has nothing
legitimate to be for. You still choose the icon yourself — the generator has no
network access and cannot fetch one.

## Transport security

A generated app is HTTPS-only and validates against Android's built-in CAs, which is
all most sites need. Self-hosted setups sometimes need less, so each site can also
carry:

| Setting | Default | What turning it on means |
|---|---|---|
| Plaintext HTTP | **off** | `http://` works, and HTTPS pages may pull in HTTP subresources. Traffic is unencrypted and unauthenticated |
| Accept invalid certificates | **off** | Self-signed, expired and wrong-host certificates all pass. Anything on the path can impersonate the site |
| Trust user-installed CAs | **off** | Your own or a corporate root is accepted alongside the built-in set |
| Trust Android's built-in CAs | on | Off (available only once your own CAs are trusted) accepts nothing but the CAs you installed yourself |

Unlike the capabilities above, **none of this is revocable in Android Settings**.
Plaintext and the trust anchors are baked into the generated APK's network security
config, and there is no toggle that puts them back. The only way to undo one is to
regenerate the app. That is also why they are per-site: a lab box on a self-signed
certificate gets its own exception without any of it reaching your bank.

For a self-signed setup, install the site's CA on the device and trust user CAs rather
than accepting invalid certificates. Both get the site loading, but the first still
authenticates the server.

## The generator has no network access

pwagen declares no `INTERNET` permission. It does not merely avoid the network, it
**cannot** reach it. Check the APK:

```console
$ aapt2 dump badging pwagen.apk | grep uses-permission
uses-permission: name='android.permission.REQUEST_INSTALL_PACKAGES'
uses-permission: name='dev.pwagen.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
```

That is the list. The first is how pwagen installs the apps it builds. The second is a
signature-level permission pwagen defines on *itself*, which AndroidX adds so that its
internal broadcast receivers are not exported to other apps. Neither grants network
access.

So pwagen never fetches a site's web app manifest to autofill its name and icon. You
supply both from your device. That is a real cost in convenience, accepted on purpose.
The tool that builds and signs your isolated apps should not itself be able to phone
home.

## Signing and backup

Generated APKs are signed with an EC P-256 key created in the Android Keystore
(StrongBox where available). The private key is **non-exportable**: apksig only ever
calls `Signature.initSign()`, so signing happens inside the TEE and the key never
enters app memory.

The key survives every ordinary app update. It is destroyed only by uninstalling
pwagen or switching install source.

**Backups contain your configuration and icons, never the key.** On a new device you
import the backup, pwagen mints a fresh key, and regenerates every app. Package names
are derived deterministically from each site's host. A restored backup reproduces
*identical* package names, so your firewall rules still apply by name.

**One app at a time.** The ⤒ on a row writes that single web app — settings and icon —
to its own file, and ⤓ in the top bar reads it back. A single app is written in the
same container as a full backup, just holding one definition, so there is one format
either way: import takes whichever you hand it, and anything whose package name
already exists is replaced. That makes a hardened setup something you can pass to
someone else without handing over the rest of your list.

## Requirements

- **Android 11+** (API 30)
- Built for **GrapheneOS**, where the system WebView is Vanadium. Works on stock
  Android too, with whatever WebView is installed.
- "Install unknown apps" permission, since pwagen installs the APKs it generates.

## Building

No system JDK is required if you have Android Studio. Its bundled JBR is enough.

```bash
export JAVA_HOME=/path/to/android-studio/jbr
./gradlew :app:assembleRelease
```

The generator embeds `:shell`'s unsigned release APK as an asset, built in the same
invocation. The template is never checked in, so the shell runtime and the generator
that ships it cannot drift apart.

### Tests

The generation pipeline is pure Java and runs on the desktop JVM. Its tests generate
real APKs and verify them with the same `aapt2`, `apksigner` and `zipalign` that
Android ships, because the mistakes that matter here (a compressed `resources.arsc`, a
misaligned entry, a malformed signature) produce files that look fine and then fail to
install on a real phone.

```bash
./gradlew :config:test :app:testDebugUnitTest
```

## Project layout

| Module | Role |
|---|---|
| `:app` | The generator. Compose UI, APK rewriting and signing. No `INTERNET`. |
| `:shell` | The WebView runtime. Becomes the template APK. Network-facing, kept minimal. |
| `:config` | Config schema and domain matcher, shared by both. |

## Licence

[GPL-3.0-only](LICENSE). Copyleft is deliberate for a privacy tool: a closed,
subtly-altered fork is the thing worth guarding against.
