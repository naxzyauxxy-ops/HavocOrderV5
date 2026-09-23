# HavocOrders

Player-driven buy orders for **Paper / Purpur 1.21.7+**, with a chest-menu interface laid out
entirely from config.

Players post orders ("I'll pay $12 each for 512 diamonds"), anyone can deliver and get paid
instantly, and the owner collects, drops, or sells the loot.

## The menus

Everything is a chest menu, laid out from `menus.yml`. Each menu declares its size, which
slots hold content, and a fixed slot for every named control, so buttons never move as
players page through:

```yaml
ORDERS:
  SIZE: 54
  INFO-SLOT: 4
  CONTENT-SLOTS: [ 10,11,12,13,14,15,16, 19,20,21,22,23,24,25, 28,29,30,31,32,33,34 ]
  SLOTS:
    PREVIOUS: 45
    SORT: 47
    FILTER: 48
    SEARCH: 49
    CLOSE: 52
    NEXT: 53
```

Content sits in an inset rectangle with a one-slot border, controls run along the bottom
row, and every empty slot is filled with a border pane, so a half-empty page still looks
deliberate rather than like a broken grid:

```yaml
FILLER:
  MATERIAL: "GRAY_STAINED_GLASS_PANE"
  NAME: " "
```

Each button has its own `MATERIAL`, `LABEL` and `TOOLTIP`. Entries that represent an item —
an order, a listing, waiting loot — use the real item as their icon, so the menu shows what
it is about rather than a wall of identical panes.

The body text of each menu lives on an information item at `INFO-SLOT`, which is also where
the item being ordered, bought or previewed is shown.

**One thing to keep in step:** the per-page counts in `config.yml` must match the number of
`CONTENT-SLOTS` on the matching menu. Set 21 slots and 30 per page, and nine entries have
nowhere to be drawn.

Text entry — search, prices, amounts — is typed in chat, since a chest has nowhere to type.
Other players cannot see it, but chat-logging plugins can.

## Requirements

- **Server:** Paper or Purpur **1.21+**
- **Client:** any — the interface is chest menus, so Java and Bedrock both work
- **Vault** plus an economy provider (EssentialsX Economy, CMI, ...)
- JDK 21 to build

## Screens

| Menu | What it does |
| --- | --- |
| Orders | The board: paged, sortable, filterable, searchable |
| Deliver | Progress bar, what you're carrying, payout preview, quick-amount buttons |
| Your Orders | Your active orders, escrow total, loot counter, delivery-alert toggle |
| Manage Order | Per-order detail, collect or cancel — also where your own orders on the board lead |
| New Order | Item + amount + price, all in one dialog |
| Item Picker | Every orderable item, paged with filter and search |
| Enchant Picker | Every enchantment and level, for enchanted books |
| Collect | Your loot: collect, drop, or sell |
| Confirm dialogs | Cancel order, sell all, drop all |

## Commands

| Command | Description |
| --- | --- |
| `/orders` | Open the board — everything else is reachable from inside |
| `/orders reload` | Reload config and dialogs |

Sub-commands for your orders, collecting, and selling were removed; those are buttons now.

Permissions: `havocorders.use` (default true), `havocorders.admin` (default op).

## Number shorthand

Every amount and price field accepts shorthand, in input and output:

- `1k` = 1,000 · `2.5k` = 2,500 · `1m` = 1,000,000 · `3b`, `1t`, `1q`
- `$` signs, commas and underscores are ignored, so `$1,250` works
- Amount fields also accept `all`, `max`, and `half`

Display abbreviates the same way (`1.23m`). Turn that off with
`SETTINGS.ABBREVIATE-NUMBERS: false` — input shorthand keeps working either way.

## Renamed items and search

Search matches the real item type, not a custom display name, so a renamed item cannot
surface under a type it is not. Seller names remain searchable.

## Java and Bedrock

Chest menus render natively on both, so there is no dialog translation involved and no
version floor beyond the plugin's own. The one gap is Bedrock's font, which has no glyphs
for the small caps the menus use; those are rewritten to plain ASCII for Bedrock players
only, via Floodgate. Java players are unaffected.

```yaml
BEDROCK:
  ASCII-LABELS: true
```

## Dialog size

Multi-action dialogs lay their buttons out in a grid, and the API defaults to **2 columns**,
which is why the window looked cramped. Size is now configurable:

```yaml
SETTINGS:
  DIALOG:
    COLUMNS: 3        # grid width
    BUTTON-WIDTH: 200 # pixels per button, 1-1024
    ITEM-SIZE: 48     # item preview size, 1-256 (vanilla default is 16)
  ORDERS-PER-PAGE: 21
  ITEMS-PER-PAGE: 27
  COLLECT-PER-PAGE: 15
```

Roughly, window width is `COLUMNS x BUTTON-WIDTH`. Three 200px columns fills most of a
normal-scale screen; push `BUTTON-WIDTH` toward 300 or `COLUMNS` to 4 if you run a low GUI
scale. Any dialog can override the column count on its own with a `COLUMNS:` key in
`menus.yml` — the deliver screen uses 2 because it is mostly text and inputs.

Dialogs scroll, so the per-page counts are far higher than a chest GUI allowed: 21 orders
per page in a 3-wide grid is seven rows at a glance.

Back and Close now sit in the dialog's dedicated footer slot rather than taking up a grid
cell, so the grid is all content.

## Shulker support

Deliveries read and pull from shulker boxes the player is carrying, so nobody has to
unpack a box first. The deliver screen splits the count:

```
In your inventory: 128
In your shulkers:  1,728
```

Loose stacks are taken first and shulker contents second, so boxes stay packed as long as
possible. Emptied boxes are kept, never consumed.

```yaml
SETTINGS:
  SHULKERS:
    DELIVER-FROM-SHULKERS: true
    CACHE-MILLIS: 1000
```

**Why the cache exists:** reading a shulker's contents deserialises its block state, which
is not cheap, and the order board asks "how many do you have" once per visible order. With
21 orders on screen and a few boxes in your inventory, an uncached implementation would
unpack every box twenty-odd times per redraw. One scan now counts everything you carry and
that snapshot is reused for a second. Anything that moves items invalidates it, and
deliveries always re-scan before touching your inventory, so the cache can only ever make a
displayed number a second stale — never the amount that actually moves.

Note that a *filled* shulker is only matched by an identically filled one, because item
matching compares full item data. Ordering "a shulker box" means an empty one.

## The deliver screen

The busiest screen, so it gets the most detail:

- A progress bar and percentage for the order
- How much is still wanted, and how much the order has paid out so far
- How many matching items you are carrying, how many you can deliver right now, and
  exactly what that pays
- What filling the whole order would pay
- A free-text amount field accepting `1k`, `2.5k`, `half`, `all`
- **Quick-amount buttons** from `QUICK-AMOUNTS` in `menus.yml` (default 64 / 576 / 1728 —
  a stack, nine stacks, a shulker). They only appear when you can actually deliver that
  many, so the screen never shows a button that would fail.

Set your own amounts per server:

```yaml
DELIVER:
  COLUMNS: 3
  QUICK-AMOUNTS: [ 64, 576, 1728 ]
```

`{progress}`, `{percent}`, `{held}`, `{deliverable}`, `{payout}` and `{full_payout}` are
available in its body lines and tooltips.

## External settings menus (PlaceholderAPI)

Registers the `havocorders` expansion when PlaceholderAPI is installed, plus a standalone
toggle command, so a settings menu can drive the alert preference without knowing anything
about this plugin's dialogs.

| Placeholder | Value |
| --- | --- |
| `%havocorders_alerts_status%` | Styled ON / OFF |
| `%havocorders_alerts_raw%` | `true` / `false` |
| `%havocorders_active%` | Live orders |
| `%havocorders_collectable%` | Items waiting to collect |
| `%havocorders_escrow%` | Money tied up in outstanding orders |

Command: `/toggleorderalerts` (alias `/orderalerts`).

The ON/OFF text is config-driven, since it renders inside whatever menu plugin reads it:

```yaml
PLACEHOLDERS:
  ENABLED-TEXT: "<green>ON"
  DISABLED-TEXT: "<red>OFF"
```

Use `&a` / `&c` instead if your menu expects legacy colour codes, or plain `ON` / `OFF`.

## Importing from the original DonutOrders

Drop the old plugin's `orders.db` into `plugins/HavocOrders/` as `import.db` and start the
server, or run `/orders import [file]`. The old schema (`orders` + `profiles`) is read
directly, including its `BukkitObjectOutputStream` item blobs.

What comes across:

| Legacy | Becomes |
| --- | --- |
| `id` (8 chars) | A fixed UUID derived from it, so re-importing skips duplicates |
| `deliver` / `deliverName` | Order owner |
| `maxAmount` / `currentAmount` / `collectedAmount` | Amount, delivered, collected |
| `unitItemPrice` / `currentPaid` | Price and payout history |
| `serializedItem` | The exact item, falling back to `material` if the blob won't read |
| `createdDate` / `expireDate` | Timestamps (`DATE-FORMAT`, `TIMEZONE`) |
| `profiles.orderAlerts` | Each player's delivery-notification choice |

**The importer never moves money and never drops loot.** Delivered and paid figures come
across as history only, because the old plugin already handled those payments. Uncollected
items stay owed and appear in the collect screen as normal.

Two settings deserve a decision before you run it:

```yaml
IMPORT:
  ESCROW-ALREADY-HELD: true
  EXPIRY:
    MODE: EXTEND      # or KEEP
    EXTEND-DAYS: 7
```

`ESCROW-ALREADY-HELD` decides whether cancelling an imported order pays a refund. The
original plugin charged order value up front and refunded undelivered items, so `true` is
correct for it — the money exists and the player is owed it. Set it to `false` if your old
setup did not hold that money, otherwise cancelling imported orders mints currency. Orders
carry this flag individually, so imported and native orders can coexist safely.

`EXPIRY.MODE` handles orders that expired while the old plugin was down. `EXTEND` gives
them a fresh window and keeps them live. `KEEP` imports them as expired — loot is still
collectable, but no refund is issued, since the old plugin owned that decision.

Remove the old plugin before importing so the two are not running against one economy.
The file is renamed to `*.imported` afterwards so a restart doesn't re-read it.

## Choosing enchantments on an order

The New Order screen has an **Enchantments** button once an item is picked. It lists only
the enchantments that apply to that item, and each one opens a level picker. An order can
then be for a Sharpness V, Unbreaking III netherite sword rather than just a sword.

```yaml
SETTINGS:
  ENCHANTS:
    ALLOW-UNSAFE: false      # allow enchantments that do not fit the item, and over-max levels
    MAX-UNSAFE-LEVEL: 10
```

## How a delivery is judged

```yaml
SETTINGS:
  MATCHING:
    ENCHANTMENTS: "AT-LEAST"
    IGNORE-DAMAGE: true
    MIN-DURABILITY-PERCENT: 0
```

`AT-LEAST` means a delivered item must carry at least the enchantments ordered, at that
level or higher; extras are a bonus rather than a mismatch. `EXACT` requires the
enchantment set to match precisely.

`IGNORE-DAMAGE` fixes elytra and tool orders. Item matching normally compares every scrap
of data, so an elytra someone has actually flown is a different item from a pristine one
and could never fill an order for "an elytra" — deliveries failed seemingly at random,
depending on whether the deliverer's item happened to be untouched.

Because wear is now ignored, **the buyer receives the exact item that was handed in**, not
a fresh copy of the template. Orders keep the real delivered stacks rather than a count, so
a battered elytra arrives battered. Without that, ignoring wear would have quietly turned
the order board into a free repair service.

Use `MIN-DURABILITY-PERCENT` if you want a floor — set it to 50 and anything under half
durability is refused.

## Spawner orders (HavocSpawners)

Players can order spawners you have explicitly allowed. Hold one and run:

```
/orders spawners add zombie Zombie Spawner
/orders spawners list
/orders spawners remove zombie
```

Allowed spawners then appear in the item picker like any other item, and are exempt from
the `SPAWNER` blacklist entry — the raw vanilla block stays blocked, the allowed spawner
does not.

**Why the item is captured rather than named.** A spawner is stored as an item with its
entity type, item material, upgrade level, stack size, stored loot and stored experience in
its item data. Capturing the item you are holding means the order template is byte-identical
to what HavocSpawners produces, with no assumptions about that format.

**Why matching is not `isSimilar`.** Delivery normally compares every scrap of item data.
For spawners that is wrong: a zombie spawner with a few stacks of rotten flesh inside is a
different item to `isSimilar` than an empty one, so an order would never fill. Spawner
orders match on identity instead — entity type, item material, and optionally level —
ignoring stored loot, experience and stack size.

```yaml
SPAWNERS:
  ENABLED: true
  MATCH-LEVEL: true            # a level 3 is a different order from a level 1
  REQUIRE-EMPTY-STORAGE: true  # refuse spawners that still hold loot
  REQUIRE-SINGLE: true         # refuse stacked spawners
```

`REQUIRE-EMPTY-STORAGE` matters: an order hands the buyer a clean copy of the template, so
accepting a full spawner would silently delete whatever was inside it. Off by default it
would be a quiet item-loss bug, so it is on.

The hook is entirely reflective and optional. Without HavocSpawners installed the plugin
runs exactly as before and spawner orders never appear.

If `/orders spawners add` is not behaving, run:

```
/orders spawners status
```

It reports exactly why support is off — not installed, not enabled yet, disabled in config,
or an API mismatch — instead of failing silently. The hook also retries on demand, so it
recovers if HavocSpawners enables after this plugin.

## Order limits

`MAX-ORDERS-PER-PLAYER` defaults to **0, meaning unlimited**. Set it to a number to cap
active orders per player; admins with `havocorders.admin` bypass any cap.

```yaml
SETTINGS:
  MAX-ORDERS-PER-PLAYER: 0
```

Escrow still applies per order, so a player's real limit is their balance.

## Bulk orders

Orders are built for volume. Defaults:

| Setting | Default |
| --- | --- |
| `MAX-ITEM-AMOUNT` | 1,000,000 items per order |
| `MAX-PRICE-AMOUNT` | 10,000,000 per item |
| `MAX-ORDER-VALUE` | 1,000,000,000 total (`0` disables the check) |

`MAX-ORDER-VALUE` is the one that matters: amount x price is what gets pulled from the
player's balance up front, so the ceiling stops someone posting an order worth more than
your economy can represent.

Large orders never materialise as item stacks. Loot is tracked as a count against the
order, the collect screen shows one entry per order rather than one per stack, and drops
cut stacks off a counter as they are released. A million-item order costs the same memory
as a one-item order.

## Dropping loot

The Collect dialog has three drop buttons:

- **Drop Page** — the entries currently on screen
- **Drop N Pages** — the next N pages from where you are (`SETTINGS.DROP.PAGE-BATCH`)
- **Drop All** — everything, behind a confirmation dialog

Stacks are generated as they are dropped, a few per tick (`MAX-STACKS-PER-TICK`, default
24), so "Drop All" on a million items is a slow trickle rather than a frozen server.
`MAX-TOTAL-STACKS` is an optional ceiling on one drop action (`0` = no limit). Book-keeping
happens up front, so nothing is ever owed twice — and if you log out mid-drop, the
remainder falls where you were standing instead of vanishing.

Because collect entries are per order rather than per stack, the page buttons only matter
if you have more waiting orders than `COLLECT-PER-PAGE`.

## Durability of data, and why writes are immediate

Anything that hands a player an item or moves money is written to the database straight
away, not on the periodic timer. The timer still exists for low-risk updates, and bursts
are collapsed into a single batch, but the window between "player has the item" and
"database knows" is now milliseconds rather than up to 30 seconds.

That gap was a real duplication bug: on a crash, `kill`, or a plugin-manager unload, the
unwritten records came back on restart while the player already had the goods.

`SAVE-INTERVAL-SECONDS` is now only a safety net. Lowering it is no longer how you protect
against duplication.

## Startup and load order

The plugin no longer disables itself when Vault has no economy provider yet. Economy
plugins register their Vault service during their own enable, so load order alone could
leave this plugin dead until it was reloaded by hand. It now waits, retrying once a second
for a minute, and logs when it hooks in. Commands report the missing economy until then.

## Live config reloading

`config.yml` and `menus.yml` are re-read when their timestamps change, so edits apply
without a restart or a reload command:

```yaml
RELOAD-WATCH-SECONDS: 5   # 0 disables
```

This covers settings and menu text only. It does not touch orders, listings or money, and
it is unrelated to duplication.

## Updating

New settings from a plugin update are written into your existing `config.yml` and
`menus.yml` on startup. Your values are never changed, nothing is removed, and the
previous file is saved as `config.yml.bak`. The console lists every key it added.

```yaml
AUTO-UPDATE-CONFIG: true   # set false to manage the files yourself
```

Two things it deliberately does not do: it will not change a value you have already set,
and it will not delete keys it does not recognise, since those are assumed to be yours.
So a *new* setting appears by itself, but a *changed default* is still yours to apply.

## Performance

- The full order set lives in memory; nothing queries the database during play.
- Writes go into a dirty set flushed by **one** batched async transaction every 30s
  (`SAVE-INTERVAL-SECONDS`), so a busy server doesn't spawn a thread per delivery.
- Orders are indexed by owner, so "your orders" never scans the whole set.
- The board caches its filtered, sorted result per player and rebuilds only when the order
  set actually changed (a version counter) or the player changed a filter — a page turn is
  a list slice.
- Order quantity is a number, never a list of stacks, so bulk orders cost nothing extra.
- The item picker is pre-bucketed by category with pre-lowercased names, so filtering is a
  map lookup and searching is one pass over an already-narrowed list.
- Dialog buttons use local click callbacks, so there is no global event handler firing for
  every dialog click on the server.
- The only repeating tasks are the expiry sweep (60s) and the save flush (30s).

## Sell All

`SELL.PRICES` sets the per-item value; anything missing falls back to `SELL.DEFAULT-PRICE`
(`0` = not sellable, stays in the collect list). `SELL.MULTIPLIER` scales everything.

## Config files

- `config.yml` — database, economy, limits, sell prices, drop safety, messages
- `menus.yml` — every layout, title, body line, button label, material and tooltip

## Build

```
mvn clean package
```

Jar lands in `target/HavocOrders-1.0.0.jar`. CI is in `.github/workflows/build.yml`.

## Layout

```
net.eclipse.havocorders
├── HavocOrders            entry point, config, scheduling, spread-drop
├── command/               /orders
├── dialog/                Screen base, Dialogs helpers, one class per screen
├── economy/               Vault hook, sell prices
├── manager/               OrderManager, ItemCatalogue, Session, SessionManager
├── model/                 Order, OrderStatus, SortOption
├── storage/               SqlStorage (SQLite / MySQL, batched)
└── util/                  Text, NumberUtil, ItemNames, Category, ...
```
