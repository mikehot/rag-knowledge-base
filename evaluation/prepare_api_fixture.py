#!/usr/bin/env python3
"""Provision a disposable API-backed fixture for the Golden Dataset.

The script uses only the existing authenticated admin/document APIs. Credentials
and the manifest path are supplied outside the repository; no secrets are stored
in source control. Run this only against a local or explicitly disposable tenant.
"""

from __future__ import annotations

import argparse
import json
import mimetypes
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parent.parent
DEFAULT_SAMPLE = ROOT / "sample_faq.md"
DEFAULT_KB_ID = "00000000-0000-0000-0000-000000000101"
FIXTURE_DOCS = {
    "finance-policy.md": "评测专用财务制度：报销资料仅供 finance 评测主体使用。",
    "hr-policy.md": "评测专用 HR 制度：薪酬等级仅供 hr 评测主体使用。",
    "device-installation.md": """# SmartLock X1 Pro 安装与验收标准

## 安装硬性指标

- 门框与锁舌面板的安装间隙必须保持在 2-4mm。
- 锁舌伸缩必须顺畅，连续执行 10 次不得出现卡顿或回弹失败。
- 验收时需要记录门锁序列号、安装照片和固件版本。

## 交付确认

安装完成后，工程师应分别验证机械钥匙、指纹、App 开锁和一次远程开门，并在工单中记录验收结果。安装标准与售后响应标准是两份独立资料，不能用售后 SLA 代替现场验收指标。
""",
    "support-sla.md": """# SmartLock 工程支持 SLA

## 远程开门故障

远程开门失败时，工程师首先确认设备在线状态、远程开锁开关和人脸或指纹二次验证；不要直接建议关闭安全校验。

## 响应和记录要求

- P1 远程开门故障的首次响应时间不超过 30 分钟。
- 工单必须记录设备序列号、发生时间、requestId 和现场网络运营商。
- 若 2 小时内无法恢复，应升级到二线平台组，并保留用户授权和审计记录。
""",
    "release-notes-v2.md": """# SmartLock X1 Pro 固件 2.2 发布说明

## 版本变化

固件 2.2 于 2026-08-15 发布。离线场景下最多保留 12 条临时密码，恢复联网后按创建时间同步；低电量提醒阈值从 10% 调整为 15%。

## 升级注意事项

升级前必须确认设备电量高于 30%，升级过程中不能拔出电池。若升级后 App 显示版本仍为 2.1，应先记录设备序列号和升级任务编号，再联系平台组，不要重复刷写。
""",
    "long-ops-manual.md": """# 门锁平台夜间升级运维手册

## 变更准备

夜间变更开始前，值班工程师要确认变更单、值班群、回滚负责人和数据库备份状态。所有操作必须使用变更单中的任务编号，不能以临时口头指令替代审批。

## 现场检查

升级前检查设备在线率、消息队列积压、索引任务状态、审计写入延迟和最近一次备份结果。检查结果需要写入变更记录，异常项目必须在执行前升级给值班负责人。

## 过程记录

升级过程中每 10 分钟记录一次成功数、失败数、失败原因和当前版本。遇到单个设备失败时先隔离设备，不要直接扩大重试范围。平台组和业务组应使用同一个任务编号沟通，避免重复执行。

## 数据保护

任何涉及用户、设备或权限的数据变更，都要保留操作人、时间、任务编号和审计结果。导出的诊断文件只能放在受控目录，工单关闭后按保留策略清理。

## 依赖检查

变更前还要检查服务版本、配置版本、消息队列消费者数量、数据库连接池和对象存储空间。任何一项检查无法完成时，变更负责人必须暂停操作并在工单中写明原因。不能因为单台设备表现正常，就跳过平台级依赖检查。

## 分批策略

设备升级应按照区域、租户和硬件批次分批执行。第一批只允许使用小比例设备验证，观察至少一个完整监控周期后才能扩大范围。每一批都要保存开始时间、结束时间、成功率和异常样本，异常样本不能被下一批覆盖。

## 监控要求

值班人员需要同时观察 API 错误率、索引延迟、设备心跳、远程开门失败率和权限拒绝数量。指标出现突增时，先冻结扩大范围，再根据 requestId、任务编号和审计记录定位。告警恢复后仍要在变更单中记录持续时间和处理人。

## 通知规则

涉及用户可见功能的变更，应在开始前通知业务联系人，在完成或回滚后发送结果摘要。通知中只放脱敏后的数量、版本和状态，不发送用户资料、访问令牌或完整诊断正文。

## 回滚规则

夜间升级失败后，必须在 30 分钟内完成回滚；回滚前要保留升级日志、任务编号和数据库迁移记录。回滚完成后需要重新检查设备在线率，并由值班负责人在变更单中确认恢复结果。
""",
}


def load_object(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{path} must contain a JSON object")
    return value


def request(
    base_url: str,
    method: str,
    path: str,
    token: str | None = None,
    payload: dict[str, Any] | None = None,
    body: bytes | None = None,
    content_type: str = "application/json",
    timeout: float = 60.0,
) -> tuple[int, dict[str, Any]]:
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if payload is not None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    if body is not None:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(
        f"{base_url.rstrip('/')}{path}",
        data=body,
        headers=headers,
        method=method,
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            status = response.status
            raw = response.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        status = exc.code
        raw = exc.read().decode("utf-8", errors="replace")
    try:
        value = json.loads(raw)
    except json.JSONDecodeError:
        value = {"code": status, "message": raw[:500], "data": None}
    if not isinstance(value, dict):
        value = {"code": status, "message": "non-object API response", "data": None}
    return status, value


def login(base_url: str, credentials: dict[str, Any], timeout: float) -> str:
    status, envelope = request(base_url, "POST", "/api/auth/login", payload=credentials, timeout=timeout)
    data = envelope.get("data")
    if status != 200 or envelope.get("code") != 0 or not isinstance(data, dict) or not data.get("token"):
        raise RuntimeError(f"login failed for {credentials.get('username')} (HTTP {status}, code {envelope.get('code')})")
    return str(data["token"])


def multipart(field: str, filename: str, content: bytes, content_type: str) -> tuple[bytes, str]:
    boundary = f"----rag-eval-{uuid.uuid4().hex}"
    prefix = (
        f"--{boundary}\r\n"
        f"Content-Disposition: form-data; name=\"{field}\"; filename=\"{filename}\"\r\n"
        f"Content-Type: {content_type}\r\n\r\n"
    ).encode("utf-8")
    suffix = f"\r\n--{boundary}--\r\n".encode("utf-8")
    return prefix + content + suffix, f"multipart/form-data; boundary={boundary}"


def data_of(status: int, envelope: dict[str, Any], operation: str) -> Any:
    if status < 200 or status >= 300 or envelope.get("code") != 0:
        raise RuntimeError(f"{operation} failed (HTTP {status}, code {envelope.get('code')}): {envelope.get('message')}")
    return envelope.get("data")


def find_or_create_users(
    base_url: str,
    admin_token: str,
    actor_credentials: dict[str, dict[str, Any]],
    timeout: float,
) -> dict[str, str]:
    status, envelope = request(base_url, "GET", "/api/admin/users", token=admin_token, timeout=timeout)
    users = data_of(status, envelope, "list users")
    if not isinstance(users, list):
        raise RuntimeError("list users returned an unexpected payload")
    by_username = {str(item.get("username")): item for item in users if isinstance(item, dict)}
    result: dict[str, str] = {}
    for actor_id, credentials in actor_credentials.items():
        username = str(credentials.get("username", ""))
        if not username or not credentials.get("password"):
            raise ValueError(f"actor {actor_id} requires username and password")
        existing = by_username.get(username)
        if existing is None:
            payload = {
                "username": username,
                "password": credentials["password"],
                "displayName": f"Golden Eval {actor_id}",
                "roleCodes": credentials.get("roleCodes") or ["EMPLOYEE"],
            }
            status, envelope = request(base_url, "POST", "/api/admin/users", token=admin_token, payload=payload, timeout=timeout)
            if status == 409:
                status, envelope = request(base_url, "GET", "/api/admin/users", token=admin_token, timeout=timeout)
                users = data_of(status, envelope, "refresh users")
                by_username = {str(item.get("username")): item for item in users if isinstance(item, dict)}
                existing = by_username.get(username)
            else:
                existing = data_of(status, envelope, f"create user {username}")
        if not isinstance(existing, dict) or not existing.get("id"):
            raise RuntimeError(f"could not resolve user {username}")
        result[actor_id] = str(existing["id"])
    return result


def upload_document(base_url: str, admin_token: str, filename: str, content: bytes, timeout: float) -> str:
    body, content_type = multipart("file", filename, content, mimetypes.guess_type(filename)[0] or "text/markdown")
    status, envelope = request(
        base_url,
        "POST",
        "/api/documents/upload",
        token=admin_token,
        body=body,
        content_type=content_type,
        timeout=timeout,
    )
    data = data_of(status, envelope, f"upload {filename}")
    if not isinstance(data, dict) or not data.get("documentId"):
        raise RuntimeError(f"upload {filename} returned no documentId")
    return str(data["documentId"])


def wait_ready(base_url: str, admin_token: str, document_id: str, timeout: float, attempts: int = 40) -> dict[str, Any]:
    for _ in range(attempts):
        status, envelope = request(base_url, "GET", f"/api/documents/{document_id}", token=admin_token, timeout=timeout)
        data = data_of(status, envelope, f"read document {document_id}")
        if isinstance(data, dict) and data.get("status") in {"ready", "failed"}:
            if data.get("status") != "ready":
                raise RuntimeError(f"document {document_id} indexing failed: {data.get('errorMsg')}")
            return data
        time.sleep(2)
    raise TimeoutError(f"document {document_id} did not become ready")


def grant_document_read(base_url: str, admin_token: str, document_id: str, principal_id: str, timeout: float) -> None:
    payload = {"principalType": "USER", "principalId": principal_id, "permission": "READ"}
    status, envelope = request(
        base_url,
        "POST",
        f"/api/documents/{document_id}/acl",
        token=admin_token,
        payload=payload,
        timeout=timeout,
    )
    if envelope.get("code") == 409:
        return
    data_of(status, envelope, f"grant document read {document_id} to {principal_id}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--admin-credentials", type=Path, required=True, help="External JSON: {username,password}")
    parser.add_argument("--credentials", type=Path, required=True, help="External actor map keyed by dataset acting_user.id")
    parser.add_argument("--sample-faq", type=Path, default=DEFAULT_SAMPLE)
    parser.add_argument("--manifest", type=Path, required=True, help="Disposable JSON manifest path")
    parser.add_argument("--timeout", type=float, default=60.0)
    args = parser.parse_args()
    try:
        admin_credentials = load_object(args.admin_credentials)
        actor_credentials = load_object(args.credentials)
        admin_token = login(args.base_url, admin_credentials, args.timeout)
        actor_ids = find_or_create_users(args.base_url, admin_token, actor_credentials, args.timeout)

        documents = {
            "sample_faq.md": upload_document(args.base_url, admin_token, "sample_faq.md", args.sample_faq.read_bytes(), args.timeout),
        }
        for filename, content in FIXTURE_DOCS.items():
            documents[filename] = upload_document(args.base_url, admin_token, filename, content.encode("utf-8"), args.timeout)
        for _filename, document_id in documents.items():
            wait_ready(args.base_url, admin_token, document_id, args.timeout)

        # The shared FAQ is granted to every non-admin actor. Stress documents
        # are granted only to employee/auditor actors; the outsider must remain
        # unable to retrieve the firmware notes for the ACL case.
        for actor_id, principal_id in actor_ids.items():
            if actor_id != "demo.admin":
                grant_document_read(args.base_url, admin_token, documents["sample_faq.md"], principal_id, args.timeout)
            if actor_id in {"demo.employee", "demo.auditor"}:
                for filename in ("device-installation.md", "support-sla.md", "release-notes-v2.md", "long-ops-manual.md"):
                    grant_document_read(args.base_url, admin_token, documents[filename], principal_id, args.timeout)

        manifest = {
            "knowledgeBaseId": DEFAULT_KB_ID,
            "actors": actor_ids,
            "documents": documents,
            "policyDocumentsGrantedToActors": False,
            "stressDocumentsGrantedTo": ["demo.employee", "demo.auditor"],
        }
        args.manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"fixture ready; manifest written to {args.manifest}")
        return 0
    except (OSError, ValueError, RuntimeError, TimeoutError, urllib.error.URLError) as exc:
        print(f"fixture preparation error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
