#!/usr/bin/env python3
"""Writes what the contract tests need on a Suwayomi-Server: SQL for the works and chapters, and the CBZ files.

Portable copy of the seeder of the Karasu app's live tests, so this repository does not depend on the app's.

usage: seed.py <server-root> <sql-out> [works=40]

Works are /live/m01.. on the server's own (local) source, all in user 1's library. Chapters c1 and c2
are downloaded (a real 3-page CBZ on disk and the flags the server checks); c3 and c4 are not. The SQL
is plain ANSI so the same file runs on H2 and on PostgreSQL.
"""
import os
import struct
import sys
import zipfile
import zlib


def png(rgb):
    w, h = 32, 48
    raw = b"".join(b"\x00" + bytes(rgb) * w for _ in range(h))

    def chunk(kind, data):
        body = struct.pack(">I", len(data)) + kind + data
        return body + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw))
        + chunk(b"IEND", b"")
    )


def main():
    root, sql_out = sys.argv[1], sys.argv[2]
    works = int(sys.argv[3]) if len(sys.argv) > 3 else 40
    lines = []
    for n in range(1, works + 1):
        title = "Live M%02d" % n
        url = "/live/m%02d" % n
        lines.append(
            "INSERT INTO manga(url, title, initialized, status, source, memo) "
            "VALUES ('%s', '%s', TRUE, 1, 0, '{}');" % (url, title)
        )
        lines.append(
            "INSERT INTO mangauser(manga, user_id, in_library, in_library_at) "
            "SELECT id, 1, TRUE, 1700000000 FROM manga WHERE url = '%s';" % url
        )
        for k in range(1, 5):
            downloaded = "TRUE" if k <= 2 else "FALSE"
            pages = 3 if k <= 2 else -1
            lines.append(
                "INSERT INTO chapter(url, name, chapter_number, source_order, manga, is_downloaded, page_count, memo) "
                "SELECT '%s/c%d', 'Chapter %d', %d, %d, id, %s, %d, '{}' FROM manga WHERE url = '%s';"
                % (url, k, k, k, k, downloaded, pages, url)
            )
            if k <= 2:
                lines.append(
                    "INSERT INTO chapteruser(chapter, user_id, is_downloaded, is_download_requested) "
                    "SELECT id, 1, TRUE, TRUE FROM chapter WHERE url = '%s/c%d';" % (url, k)
                )
                folder = os.path.join(root, "downloads", "mangas", "Local source", title)
                os.makedirs(folder, exist_ok=True)
                with zipfile.ZipFile(os.path.join(folder, "Chapter %d.cbz" % k), "w") as z:
                    for page in range(1, 4):
                        z.writestr("%03d.png" % page, png((40 * n % 255, 60 * k % 255, 30 * page)))
    with open(sql_out, "w") as out:
        out.write("\n".join(lines) + "\n")


main()
