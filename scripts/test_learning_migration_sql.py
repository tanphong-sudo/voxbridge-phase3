"""Host SQLite semantics regression; NOT an Android/Room/SQLCipher execution test."""
import json
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "android/app/src/main/java/com/voxbridge/store/LearningDb.kt"
SCHEMA = ROOT / "android/app/schemas/com.voxbridge.store.LearningDb/2.json"


class LearningMigrationSqlTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        self.db.execute("""CREATE TABLE learning_examples (
            normalizedSource TEXT NOT NULL PRIMARY KEY, approvedTarget TEXT NOT NULL,
            correctedSource TEXT, sourceLanguage TEXT NOT NULL, targetLanguage TEXT NOT NULL,
            sourceFrameJson TEXT, targetFrameJson TEXT, feedbackType TEXT NOT NULL,
            approvalCount INTEGER NOT NULL, rejectionCount INTEGER NOT NULL,
            lastUsedAtMs INTEGER NOT NULL)""")
        self.rows = [
            ("hello", "xin chào", "Hello!", "EN", "VI", '{"action":"STOP"}',
             '{"language":"VI"}', "EDITED_BOTH", 7, 0, 123456789),
            ("stop", "dừng lại", None, "EN", "VI", None, None, "APPROVED", 3, 2, 0),
            ("xin chao", "hello", None, "VI", "EN", None, None, "APPROVED", 1, 0, 987),
        ]
        self.db.executemany("INSERT INTO learning_examples VALUES (?,?,?,?,?,?,?,?,?,?,?)", self.rows)
        source = SOURCE.read_text()
        migration = source.split("val MIGRATION_1_2 =", 1)[1].split("/** Maps", 1)[0]
        statements = re.findall(r'db\.execSQL\(("""[\s\S]*?"""|"[^"\n]*")\)', migration)
        self.assertEqual(5, len(statements), "Migration shape changed: update extractor explicitly")
        for literal in statements:
            sql = literal[3:-3] if literal.startswith('"""') else json.loads(literal)
            self.db.execute(sql)

    def test_preserves_every_field(self):
        actual = self.db.execute("SELECT * FROM learning_examples ORDER BY normalizedSource").fetchall()
        self.assertEqual(sorted(self.rows), actual)

    def test_schema_matches_room_export(self):
        schema = json.loads(SCHEMA.read_text())["database"]
        self.assertEqual(2, schema["version"])
        entity = next(e for e in schema["entities"] if e["tableName"] == "learning_examples")
        columns = self.db.execute("PRAGMA table_info(learning_examples)").fetchall()
        self.assertEqual([(f["columnName"], f["affinity"], int(f["notNull"])) for f in entity["fields"]],
                         [(c[1], c[2], c[3]) for c in columns])
        self.assertEqual(entity["primaryKey"]["columnNames"],
                         [c[1] for c in sorted(columns, key=lambda c: c[5]) if c[5]])
        indices = {row[1]: row for row in self.db.execute("PRAGMA index_list(learning_examples)")}
        for index in entity["indices"]:
            name = index["name"]
            self.assertEqual(int(index["unique"]), indices[name][2])
            self.assertEqual(index["columnNames"],
                             [r[2] for r in self.db.execute(f'PRAGMA index_info("{name}")')])

    def test_opposite_direction_upsert_and_delete_are_independent(self):
        opposite = ("hello", "other direction", None, "VI", "EN", None, None, "APPROVED", 1, 0, 222)
        self.db.execute("INSERT OR REPLACE INTO learning_examples VALUES (?,?,?,?,?,?,?,?,?,?,?)", opposite)
        original = self.db.execute("SELECT * FROM learning_examples WHERE normalizedSource=? AND sourceLanguage=? AND targetLanguage=?",
                                   ("hello", "EN", "VI")).fetchone()
        self.assertEqual(self.rows[0], original)
        source = SOURCE.read_text()
        delete = re.search(r'@Query\("(DELETE FROM learning_examples[^"\n]+)"\)', source).group(1)
        self.db.execute(delete, {"key": "hello", "source": "VI", "target": "EN"})
        self.assertEqual(sorted(self.rows), self.db.execute("SELECT * FROM learning_examples ORDER BY normalizedSource").fetchall())

    def test_actual_candidate_query_is_bounded_and_filters_rejections(self):
        source = SOURCE.read_text()
        query = re.search(r'@Query\("(SELECT \* FROM learning_examples[^"\n]+ORDER BY[^"\n]+)"\)', source).group(1)
        rows = self.db.execute(query, {"source": "EN", "target": "VI", "limit": 1}).fetchall()
        self.assertEqual([self.rows[0]], rows)


if __name__ == "__main__":
    unittest.main(verbosity=2)
