# Anonymised game-log blueprint corpus

`LIVE/` is a channel folder of twelve synthetic log files — eleven in `logbackups/` and a current
`Game.log` — that together hold the **31** blueprint unlocks of the basetool's anonymised corpus
(`backend/src/test/resources/fixtures/blueprint-corpus/game-log-corpus-v1.json` in
`krt-profit/basetool`, epic krt-profit/basetool#2078). It is the extractor-side twin of that file:
`OwnAccountFilterTest` reads it and must find exactly 31 events of one account in 12 files.

Each file holds only a login line and blueprint lines. What they carry:

| Part | Source |
| --- | --- |
| item name, notification id, queue size | verbatim from the basetool fixture, including pack tags and a no-break space |
| build number in the file name | the event's `gameBuild` in the basetool fixture |
| handle | the pseudonym `PLAYER_A` |
| timestamps and the dates in the file names | synthetic — the fixture's hourly timestamps from `2026-01-01T10:00:00Z`, the login five minutes before a file's first event |

The split of the events into files follows their builds, in the fixture's order: a build with more
events is spread over two or three files, and the current `Game.log` holds a login only. The split was
derived from the published fixture alone; no private log was read to build it.

Nothing else from any log is in these files. Do not replace names with look-alikes or normalise
them — the corpus is only worth something because the names are exactly what the game wrote.
