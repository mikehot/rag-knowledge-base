import 'package:flutter_test/flutter_test.dart';
import 'package:rag_knowledge_base_app/data/models/document_models.dart';

void main() {
  test('parses document lifecycle state and optional disabled flag', () {
    final item = DocumentItem.fromJson({
      'documentId': 'doc-1',
      'filename': 'faq.md',
      'fileType': 'md',
      'status': 'ready',
      'chunkCount': 2,
      'errorMsg': null,
      'createdAt': '2026-09-22T08:00:00Z',
      'disabled': true,
    });

    expect(item.statusEnum, DocumentStatus.disabled);
  });

  test('parses reindex task metadata', () {
    final lifecycle = DocumentLifecycleResponse.fromJson({
      'documentId': 'doc-1',
      'status': 'processing',
      'contentVersion': 2,
      'permissionVersion': 1,
      'disabled': false,
      'deleted': false,
      'taskId': 'task-1',
    });

    expect(lifecycle.taskId, 'task-1');
    expect(lifecycle.contentVersion, 2);
  });

  test(
    'parses ACL and index task operations including failure diagnostics',
    () {
      final acl = DocumentAclItem.fromJson({
        'id': 'acl-1',
        'documentId': 'doc-1',
        'principalType': 'USER',
        'principalId': 'user-1',
        'permission': 'READ',
      });
      final task = IndexTaskItem.fromJson({
        'id': 'task-1',
        'knowledgeBaseId': 'kb-1',
        'documentId': 'doc-1',
        'operation': 'REINDEX',
        'contentVersion': 2,
        'status': 'FAILED',
        'attemptCount': 3,
        'maxAttempts': 3,
        'errorMessage': 'provider timeout',
        'nextAttemptAt': null,
        'startedAt': null,
        'finishedAt': null,
        'durationMs': 1200,
        'createdAt': '2026-09-23T08:00:00Z',
      });

      expect(acl.permission, 'READ');
      expect(task.status, 'FAILED');
      expect(task.errorMessage, 'provider timeout');
      expect(task.durationMs, 1200);
    },
  );

  test('chooses a display label from admin principal response shapes', () {
    expect(
      PrincipalOption.fromJson({
        'id': 'u1',
        'username': 'admin',
        'displayName': '管理员',
      }).label,
      '管理员',
    );
    expect(
      PrincipalOption.fromJson({
        'id': 'r1',
        'code': 'ADMIN',
        'name': '系统管理员',
      }).label,
      '系统管理员',
    );
  });
}
