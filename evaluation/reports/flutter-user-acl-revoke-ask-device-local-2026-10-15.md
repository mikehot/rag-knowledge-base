# Flutter USER ACL grant → employee ask → revoke → ask again, on a device — 2026-10-15

## Scope and environment

- Isolated Android debug package `com.example.rag_knowledge_base_app.verify`, Android 16 / API 36, USB debugging with `adb reverse tcp:8080 tcp:8080`.
- Backend and PostgreSQL/pgvector from `scripts/eval_stack.sh up` (28 public documents, disposable database, loopback only). Chat model `google/gemma-4-26b-a4b-qat` with Enable Thinking verified off, embedding model `text-embedding-bge-m3`, temperature 0.
- Users were the stack's generated synthetic accounts; the administrator was the stack's local default. Documents are public statutes and Kubernetes pages.
- This is one device session on one document and one question. It is not a quality gate, a production claim or a performance claim.

## What was done

Baseline before the session (read-only API, administrator): `labor-law.md` had two USER READ grants (`corp_hr_eval`, `corp_manager_eval`); `corp_outsider_eval` had no grant and listed zero documents.

1. Administrator signed in on the phone → knowledge base → `labor-law.md` menu → **Manage document permissions** → user `corp.outsider`, `READ` → **Grant**. An administrator API read then showed three grants, the third being `corp_outsider_eval`.
2. Signed out, signed in as `corp_outsider_eval`. The knowledge-base tab listed exactly one document, `labor-law.md` (14 chunks).
3. Asked "国家实行的工时制度是什么?每日工作时间不超过多少小时?". The answer stated the eight-hour day and 44-hour week and showed the citation chip `labor-law.md · chunk#5` ("已引用资料回答", 4,423 ms, 3,061 tokens, retrieval 9 ms).
4. Signed out, signed in as administrator, opened the same dialog, scrolled the grant list to the `4a1667f7…` row, tapped the revoke control and confirmed the warning dialog. An administrator API read then showed two grants (hr, manager unchanged).
5. Signed in as `corp_outsider_eval` again. The knowledge-base tab showed the empty state ("还没有文档"). Asked the same question: the answer was "未找到相关信息,建议转人工。" with "未找到足够资料 · 未命中资料", no citation chip, generation 0 ms.

## What this shows

- On a real device, an administrator-granted USER READ made the document visible and answerable with a citation for that employee, and revoking it made the document invisible and the same question refused with no citation.
- The change took effect on the next request (no restart, no waiting).

## What this does not show

- **Department grants were not exercised.** The evaluation stack has no departments (`/api/admin/departments` returned an empty list), so this run covers the USER principal only. ROADMAP A1 asks for a department spot check; that part is still open.
- The earlier cited answer remained visible in the app's own chat history after the revoke. That history is client-side display of a past answer; the new question was refused. Revocation does not retroactively clear what a user already saw or what a remembered conversation contains.
- One document, one question, one employee, one run; model wording was not scored.

## Small findings from the session

- The permission dialog lists existing grants by raw principal id prefix (`4a1667f7-b6ca-467a-…`), not by display name, and only the first row is visible until the list is scrolled. An administrator cannot tell which row is which employee without looking the id up. Not fixed here.
- With the soft keyboard open the login form shifts upward; a tap computed from the pre-keyboard layout lands on the wrong field. This only affected scripted input.
- While a password field is focused the device returns a black frame for `screencap` (system protection), so no password characters can appear in captured screenshots.

## Cleanup

- Revoked the grant created for this run (restored to the two fixture grants), removed the `adb reverse` mapping, signed the account out. The disposable stack remains up until `scripts/eval_stack.sh down`.
- No screenshots, credentials, device serial or document bodies were added to the repository.
