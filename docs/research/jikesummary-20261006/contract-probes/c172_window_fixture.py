"""Offline illustration of a course fixture gap, not a Hify database test."""
import json
import sqlite3

connection = sqlite3.connect(":memory:")
connection.execute("CREATE TABLE messages(id INTEGER PRIMARY KEY)")
observations = []
for size in (3, 21):
    connection.execute("DELETE FROM messages")
    connection.executemany("INSERT INTO messages VALUES (?)", ((i,) for i in range(1, size + 1)))
    oldest = [r[0] for r in connection.execute("SELECT id FROM messages ORDER BY id ASC LIMIT 20")]
    recent = sorted(r[0] for r in connection.execute("SELECT id FROM messages ORDER BY id DESC LIMIT 20"))
    if size == 3:
        assert oldest == recent == [1, 2, 3]
    else:
        assert oldest[0] == 1 and oldest[-1] == 20
        assert recent[0] == 2 and recent[-1] == 21
        assert oldest != recent
    observations.append({"messages": size, "sameSelection": oldest == recent,
                         "oldestRange": [oldest[0], oldest[-1]],
                         "recentRange": [recent[0], recent[-1]]})
connection.close()
print(json.dumps({"scope": "SQLite illustration only; not Hify acceptance", "observations": observations}))
