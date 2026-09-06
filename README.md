<div align="center">

<br>

# ✦ SwagClaims

<p align="center">
  <img src="https://img.shields.io/badge/Paper-1.21.4-667eea?style=for-the-badge" alt="Paper 1.21.4">
  <img src="https://img.shields.io/badge/Java-21-764ba2?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java 21">
  <img src="https://img.shields.io/badge/SwagAPI-Required-f0c060?style=for-the-badge" alt="SwagAPI">
  <img src="https://img.shields.io/badge/License-MIT-5865F2?style=for-the-badge" alt="MIT">
</p>

**A full land-claims plugin for Paper 1.21.4, built on SwagAPI.**
Claim, protect, and manage land the way GriefPrevention does — plus a GUI for everything, a live web dashboard, and a one-command migration path off GriefPrevention itself.

<br>

</div>

---

## ✦ Features

**Core claiming**
- Golden-shovel claim creation, resize, and abandon (single / top-level / all), with a modern `BlockDisplay`-based boundary visualization
- Trust system — Access / Container / Build / Manage levels, individual players, `public`, and permission-group targets, with subdivision inheritance
- Subdivisions and admin claims
- Per-world claim modes (Survival / Disabled), configurable minimum width/area, automatic starter claims on first join

**Economy & protection**
- Claim blocks: accrual-per-hour, a hard cap, and Vault-backed buy/sell — all per-world overridable
- Siege mode, PvP rules (fresh-spawn protection, combat-logout punishment, combat timeout), explosion/fire/piston/creature protections

**Flags** — a full per-claim (and per-world) rule system: `nomonsters`, `nomonsterspawns`, `keepinventory`, `healthregen`, `playertime`, `playerweather`, `nohunger`, `nofalldamage`, `nofiredamage`, `noexplosiondamage`, per-claim PvP overrides, and more — toggle via `/claimflag` or its GUI panel

**Claim entry titles** — configurable MiniMessage title/subtitle/actionbar on entering or leaving a claim

**GUIs** — claim list with safe teleport, an interactive trust manager (click a head to cycle trust level), claim-block gifting (`/sendclaimblocks`), and a flag toggle panel — one central dispatcher, no fragile title-string matching

**Rank-tiered expiration** *(disabled by default)* — inactive claims can expire on a schedule that varies by the owner's Vault/LuckPerms rank and the claim's own size, fully editable from the web dashboard without touching a config file or restarting

**Ecosystem integration**
- `SwagClaimsAPI` — a small static facade other plugins compile against instead of touching internals directly
- Publishes claim created/deleted/trust-changed events on SwagAPI's shared event bus
- PlaceholderAPI expansion (`%swagclaims_blocks_remaining%`, `%swagclaims_claim_name%`, `%swagclaims_claims_owned%`, and more)
- A web dashboard at `/swagapi/swagclaims/` (claim browser, expiration settings editor) via SwagAPI's shared web service

**Migration** — `/swagclaims migrategp [--dry-run] [--source <path>]` imports claims, trust, player claim-block balances, and GPFlags' flag data directly from a live GriefPrevention install. Always run `--dry-run` first; it produces a full report and never touches your database.

---

## ✦ Installation

1. Install [SwagAPI](https://github.com/swag617/SwagAPI) — **required**, SwagClaims will not enable without it
2. Download `SwagClaims.jar` from [Releases](https://github.com/swag617/SwagClaims/releases)
3. Drop it into your server's `plugins/` folder and start the server once to generate `plugins/SwagClaims/config.yml`
4. Migrating off GriefPrevention? Run `/swagclaims migrategp --dry-run` first, check the report, then run it again without `--dry-run`
5. Review expiration settings at `/swagapi/swagclaims/settings` before enabling `expiration.enabled` — it ships off by default

> **Requirements:** Paper 1.21.4+, Java 21, SwagAPI *(hard dependency)*
> **Optional:** Vault (claim-block economy, real permission-group trust), PlaceholderAPI

## License

MIT — see [LICENSE](LICENSE).
