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
}
