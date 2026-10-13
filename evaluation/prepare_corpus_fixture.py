#!/usr/bin/env python3
"""Provision the realistic-corpus fixture used by datasets/corpus_v1.jsonl.

Uploads the 28 corpus documents (cleaned Kubernetes pages and four statutes) through the
authenticated admin API and grants READ per department group:

    it-ops -> corp.sre, corp.manager      (Kubernetes pods / controllers)
    hr     -> corp.hr,  corp.manager      (labor-contract-law, labor-law)
    legal  -> corp.legal, corp.manager    (personal-information-protection-law, data-security-law)
    corp.outsider receives no grants; corp.admin only exists for protected diagnostics.

Run it only against a disposable tenant/database. It never resets or deletes anything, and credentials
and the manifest path are supplied outside the repository.
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
from pathlib import Path

from corpus_tools import corpus_documents
from prepare_api_fixture import (
    find_or_create_users,
    grant_document_read,
    load_object,
    login,
    upload_document,
    wait_ready,
)

ACCESS = {
    "it-ops": ["corp.sre", "corp.manager"],
    "hr": ["corp.hr", "corp.manager"],
    "legal": ["corp.legal", "corp.manager"],
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--admin-credentials", type=Path, required=True, help="External JSON: {username,password}")
    parser.add_argument("--credentials", type=Path, required=True, help="External actor map keyed by corp.* actor id")
    parser.add_argument("--manifest", type=Path, required=True, help="Disposable JSON manifest path")
    parser.add_argument("--timeout", type=float, default=60.0)
    args = parser.parse_args()
    try:
        admin_token = login(args.base_url, load_object(args.admin_credentials), args.timeout)
        actors = find_or_create_users(args.base_url, admin_token, load_object(args.credentials), args.timeout)
        documents: dict[str, str] = {}
        groups: dict[str, str] = {}
        for doc in corpus_documents():
            documents[doc["name"]] = upload_document(
                args.base_url, admin_token, doc["name"], doc["text"].encode("utf-8"), args.timeout
            )
            groups[doc["name"]] = doc["group"]
        for name, document_id in documents.items():
            wait_ready(args.base_url, admin_token, document_id, args.timeout, attempts=150)
        for name, document_id in documents.items():
            for actor in ACCESS[groups[name]]:
                grant_document_read(args.base_url, admin_token, document_id, actors[actor], args.timeout)
        manifest = {"actors": actors, "documents": documents, "groups": groups, "access": ACCESS}
        args.manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"corpus fixture ready: {len(documents)} documents, {len(actors)} actors; manifest {args.manifest}")
        return 0
    except (OSError, ValueError, RuntimeError, TimeoutError, KeyError, urllib.error.URLError) as exc:
        print(f"corpus fixture error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
