# Event Board

A self-contained digital-signage server written in a single Java file — event boards,
schedules, room displays, and slideshows for churches and organizations. Includes a web
admin, device-to-output routing, and multi-client boards (one account per organization).

## Features

- **TV outputs** — Board (clock, weather, Bible verse, event rundown, photo slideshow, ticker),
  Schedule (full rundown), Room display, and full-screen Slides
- **Web admin** — edit settings in one place; changes appear on all TVs automatically
- **Multi-client boards** — sign up at `/c`; each board gets its own URL (`/c/<name>`),
  login, settings, photos, and events
- **Device routing** — open `/event-board/device` on every TV, then assign its IP to an
  output in the admin DEVICES panel (TVs switch automatically)
- **Photos & branding** — per-board photo slideshow and logo
- **Events** — from ICS feeds (cached), plus weather, Bible verses, announcements, and a scrolling ticker
- **12 color themes** — Default, Dark, and 10 combined palettes (Ocean, Forest, Grape,
  Slate, Crimson, Teal, Cocoa, Sunset, Aurora, Seabreeze) applied to every output
- **Security** — session-cookie login with CSRF protection and changeable passwords

## Requirements

- JDK 11 or newer (developed and tested with Zulu JDK 17)
- No external libraries — the whole server is `DakBoard.java`

## Quick start

```bat
javac DakBoard.java
java DakBoard 8080
```

Or double-click `run-dakboard.bat` (compiles and starts on port 8080).
On first run you choose the admin password; it is stored in `admin-credential.json`
(gitignored — never commit it). The username/password can also be supplied with the
`DAKBOARD_ADMIN_USERNAME` and `DAKBOARD_ADMIN_PASSWORD` environment variables.

| URL | What it is |
| --- | --- |
| `http://localhost:8080/event-board` | Main board output (TV) |
| `http://localhost:8080/event-board/login` | Admin sign-in (lands on the admin page) |
| `http://localhost:8080/event-board/admin` | Admin control page |
| `http://localhost:8080/event-board/editor` | Limited content editor |
| `http://localhost:8080/event-board/schedule` | Schedule output |
| `http://localhost:8080/event-board/room` | Room display output |
| `http://localhost:8080/event-board/slides` | Full-screen slideshow output |
| `http://localhost:8080/event-board/device` | TV launcher — redirects each device to its assigned output |
| `http://localhost:8080/c` | Client boards — list & sign-up |
| `http://localhost:8080/c/<name>` | Client board (also `/admin`, `/schedule`, `/room`, `/slides`, `/device`) |
| `http://localhost:8080/api/event-board/*` | JSON API (settings, events, photos, login, clients) |

Old `/dakboard...` and `/lhc-events-board...` URLs redirect to the new paths.

## Files

| File | Purpose |
| --- | --- |
| `DakBoard.java` | The entire server (HTTP, auth, storage) |
| `admin.html`, `editor.html`, `login.html`, `clients.html` | Admin / editor / login / client pages |
| `board.html`, `schedule.html`, `room.html`, `slides.html` | TV output pages |
| `settings.json` | Platform board settings (managed from the admin page) |
| `clients/<name>.json` | Per-client settings |
| `photos/` | Uploaded photos (`/photos/...`; clients use `/photos/<client>/...`) |
| `events.cache.json`, `sample-events.ics` | Cached and sample calendar events |
| `run-dakboard.bat` | Windows launcher |

## Deploying

- **HTML/CSS/JS changes** are read on every request — replace the file, no restart needed.
- **Java changes** — recompile (`javac DakBoard.java`) and restart.
- Any machine on the LAN can open the TV URLs; the TVs only need the server address.

## Notes

- The platform sign-in (`/event-board/login`) manages the platform board. Client owners
  sign in on their own board's admin page and manage only their own content.
- Credential files, `clients/`, and uploads are local instance data and are gitignored.
