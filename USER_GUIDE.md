# Event Board — User Guide

Step-by-step guide for setting up and running Event Board: the admin panel, TV outputs,
devices, photos, themes, and multi-client boards.

---

## 1. Install and first run

1. Install **JDK 11+** (tested with Zulu JDK 17).
2. Open a terminal (or double-click `run-dakboard.bat` on Windows):

   ```bat
   javac DakBoard.java
   java DakBoard 8080
   ```

3. On first run you will be asked to choose the **admin password** (12+ characters).
   It is saved in `admin-credential.json` (gitignored). The username defaults to
   `admin` — override with the `DAKBOARD_ADMIN_USERNAME` and `DAKBOARD_ADMIN_PASSWORD`
   environment variables if needed.
4. Open the admin page: `http://<server>:8080/event-board/login` and sign in.
   After login you land on the admin control page.

---

## 2. Admin panel overview

Everything is edited on one page (`/event-board/admin`). Press **SAVE** (bottom of the
page) after making changes — all TVs pick them up automatically within a few seconds.

| Section | What it does |
| --- | --- |
| **Change password** | Change your own admin password (current password + new password ×2) |
| **Boards / accounts** | *(platform admin only)* List of client boards, search, and **+ CREATE NEW ACCOUNT** |
| **Moving text (bottom)** | The scrolling ticker text shown at the bottom of the outputs |
| **Slideshow announcements** | Cards that rotate in the slideshow area |
| **Bible verses (rotating)** | Search the KJV by reference (e.g. `John 3:16`) or keyword; or type one verse per line as `Text | Reference` |
| **Display settings** | Brand name & logo, main output, output design, color theme, slide duration, weather location, Outlook calendar |
| **Outputs** | Copy-paste URLs for every output type (landscape/portrait), orientation, and **Manage rooms** |
| **Devices — IP → OUTPUT** | Assign each TV's IP address to an output (see section 4) |
| **Photos (slideshow background)** | Upload and manage photos used as slideshow backgrounds |
| **Lobby slideshow** | Separate ticker + announcements for the lobby TV |
| **Editor access** | *(platform admin only)* Link, username, and password for the limited editor page |

### Display settings explained

- **BRAND NAME** — the title shown on all outputs (e.g. your church name).
- **BRAND LOGO** — filename of an image already in the Photos library (blank = text only).
- **MAIN OUTPUT** — what the main URL shows: *Board (full display)* or *Event Schedule*.
- **OUTPUT DESIGN** for room & schedule outputs: *Classic (stacked)* or *Split (side by side — slideshow left, rundown right)*.
- **SLIDE DURATION** — seconds each slideshow photo stays up (3–120).
- **WEATHER LATITUDE / LONGITUDE** — coordinates for the weather box (Silang default: `14.2306`, `120.9750`).
- **OUTLOOK CALENDAR ICS URL** — in Outlook: *Calendar → Share → Publish calendar*, then paste the ICS link. Leave blank if unused.

---

## 3. The TV outputs

| URL | Shows |
| --- | --- |
| `/event-board` | **Board** — clock, date, weather, verse, event rundown, photo slideshow, ticker |
| `/event-board/schedule` | **Schedule** — slideshow + full event rundown |
| `/event-board/room` | **Room display** — current meeting + announcements for one room |
| `/event-board/slides` | **Slides** — full-screen photos + ticker only |
| `/event-board/device` | **TV launcher** — see section 4 |

(For client boards the same outputs exist under `/c/<name>/…`.)

**Orientation:** set landscape or portrait in *Outputs → Board orientation*. Each output
has both a landscape and a portrait URL — open the one that matches how the TV is mounted.

---

## 4. Connecting the TVs (device routing)

You do not need to remember which TV shows what — assign once and the TV handles itself.

1. On the TV (or Raspberry Pi) browser, open:
   `http://<server>:8080/event-board/device`
2. The page shows **the TV's own IP address** and a "Device not yet assigned" message.
3. In the admin panel open **DEVICES — IP → OUTPUT**, click **Add**, paste that IP
   address, and choose the output (board, schedule, room, slides — landscape or portrait).
4. Press **SAVE**. The TV switches to its assigned output within ~2 seconds and will
   always follow future changes.

Notes:

- The device URL is the same for every TV — bookmark it once.
- For client boards, use `/c/<name>/device` instead (clients manage their own devices).
- Rebuilding the TV? Just open the device URL again — it remembers the assignment.

---

## 5. Color themes

**Display settings → COLOR THEME** — applies to every output (board, schedule, room,
slides) at once:

- **Default** — warm cream
- **Dark** — navy night
- **Combined colors:** Ocean blue · Forest green · Grape purple · Slate charcoal ·
  Crimson red · Teal · Cocoa brown · Sunset (peach→rose) · Aurora (teal→violet) ·
  Seabreeze (sky→mint)

All palettes are soft/eye-friendly for long display hours, and text stays clean white
for readability. Pick the theme, press **SAVE**, refresh the TVs (Ctrl+F5) if they are
already open.

---

## 6. Photos and logo

1. Open the **Photos (slideshow background)** section and upload images
   (JPG/PNG; they are stored in `photos/` on the server).
2. Uploaded photos rotate automatically in the slideshow areas of the outputs.
3. To show a logo, set **BRAND LOGO** to the photo's filename (e.g. `logo.png`).
4. The **Lobby slideshow** section holds a separate ticker and announcements for a
   lobby TV.

Client boards have their **own photo library** — photos uploaded in a client's admin
page appear only on that client's outputs.

---

## 7. Events (rundown)

- Events come from a calendar **ICS feed** (e.g. Outlook publish link) and are cached
  on the server (`events.cache.json`), so the boards keep working even if the calendar
  is temporarily unreachable.
- The **rundown** on the board/schedule/room outputs shows upcoming events grouped by
  date, with time, title, and location.
- Client boards manage their own events from their own admin page.

---

## 8. Client boards (multi-client)

One server can host many independent boards — each organization signs up and manages
its own content.

- **Sign up:** open `http://<server>:8080/c` and create an account (name, username,
  password). Each board lives at `/c/<name>` (e.g. `/c/testorg`).
- **Client sign-in:** the client owner signs in with their own username/password
  (same login page — they land on *their* admin page at `/c/<name>/admin`).
- **What a client manages:** their own ticker, announcements, verses, display settings
  (including color theme), rooms, output URLs, **devices**, **photos**, and events.
- **Platform admin** keeps the overview: the *Boards / accounts* section lists all
  client boards and links to **+ CREATE NEW ACCOUNT**.
- TV outputs for clients are public links (no login needed on the TVs):
  `/c/<name>`, `/c/<name>/schedule`, `/c/<name>/room`, `/c/<name>/slides`.

---

## 9. Editor page

The **Editor** is a limited page for people who should only update content (not system
settings):

1. Platform admin sets an **editor username and password** in *Editor access* and
   shares the link (`/event-board/editor`).
2. The editor can update text content and saves with the same settings the outputs use.

---

## 10. Updating the app

- **HTML/CSS/JS changes** (`admin.html`, `board.html`, …) — files are read on every
  request: just replace the file. **No restart needed** (refresh with Ctrl+F5).
- **Java changes** (`DakBoard.java`) — recompile and restart:

  ```bat
  javac DakBoard.java
  java DakBoard 8080
  ```

---

## 11. Troubleshooting

| Symptom | Fix |
| --- | --- |
| TV shows "Device not yet assigned" | Add its IP in **DEVICES — IP → OUTPUT**, press SAVE |
| TV not updating after changes | Refresh the TV page (Ctrl+F5); check the server is reachable from the TV |
| No events shown | Check the ICS URL in Display settings; delete `events.cache.json` to force a re-fetch |
| No weather | Verify the latitude/longitude and that the server has internet access |
| Logo not showing | The BRAND LOGO field must be the exact filename of an uploaded photo |
| Logged out unexpectedly | Sign in again — sessions expire after inactivity |
| Cannot log in | Client owners use their own credentials; the platform admin cannot be used on `/c/<name>/admin` |

---

## 12. Files on the server

| Path | Purpose |
| --- | --- |
| `settings.json` | Platform board settings (managed from the admin page) |
| `clients/<name>.json` | Per-client settings |
| `photos/` | Uploaded photos (`photos/<client>/…` for client boards) |
| `admin-credential.json` | Admin login (gitignored — keep it safe) |
| `events.cache.json` | Cached calendar events |

Back up `settings.json`, `clients/`, and `photos/` to keep all boards recoverable.
