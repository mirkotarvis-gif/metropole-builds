# metropole-builds

Mailbox between the Metropole SMP server and Claude (ClaudeBridge plugin).

- `scans/` – terrain the server uploads with `/claude scan [radius] [name]` (`.mscan.gz`)
- `builds/` – builds Claude makes for those spots (`.mbld.gz`), placed in game with `/claude preview <name>` and `/claude build <name>`

Coordinates in both formats are absolute world coordinates. Don't put tokens or passwords in this repo – it's public.
