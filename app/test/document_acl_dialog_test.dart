import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rag_knowledge_base_app/core/network/providers.dart';
import 'package:rag_knowledge_base_app/data/api/knowledge_api_client.dart';
import 'package:rag_knowledge_base_app/data/auth/session_store.dart';
import 'package:rag_knowledge_base_app/data/models/document_models.dart';
import 'package:rag_knowledge_base_app/data/repositories/knowledge_repository.dart';
import 'package:rag_knowledge_base_app/features/documents/document_acl_dialog.dart';

void main() {
  testWidgets('ACL dialog keeps field labels separated on a compact screen', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(720, 1280);
    tester.view.devicePixelRatio = 2;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          knowledgeRepositoryProvider.overrideWithValue(
            _FakeKnowledgeRepository(),
          ),
        ],
        child: MaterialApp(
          home: Builder(
            builder: (context) => Scaffold(
              body: Center(
                child: FilledButton(
                  onPressed: () => showDocumentAclDialog(
                    context,
                    documentId: 'doc-1',
                    filename: 'synthetic-acl-policy.md',
                  ),
                  child: const Text('打开 ACL'),
                ),
              ),
            ),
          ),
        ),
      ),
    );

    await tester.tap(find.text('打开 ACL'));
    await tester.pumpAndSettle();

    expect(find.text('主体类型'), findsOneWidget);
    expect(find.text('授权对象'), findsOneWidget);
    expect(find.text('权限'), findsOneWidget);

    final fields = find.byType(DropdownButtonFormField<String>);
    expect(fields, findsNWidgets(3));
    final labels = ['主体类型', '授权对象', '权限'];
    final labelBounds = labels
        .map((label) => tester.getRect(find.text(label)))
        .toList();
    final bounds = [
      tester.getRect(fields.at(0)),
      tester.getRect(fields.at(1)),
      tester.getRect(fields.at(2)),
    ];
    for (var index = 0; index < bounds.length; index++) {
      expect(
        labelBounds[index].bottom + 4,
        lessThanOrEqualTo(bounds[index].top),
      );
    }
    for (var index = 0; index < bounds.length - 1; index++) {
      expect(
        bounds[index].bottom + 8,
        lessThanOrEqualTo(bounds[index + 1].top),
      );
    }

    final dialogCard = find.descendant(
      of: find.byType(Dialog),
      matching: find.byWidgetPredicate(
        (widget) => widget is Material && widget.type == MaterialType.card,
      ),
    );
    expect(dialogCard, findsOneWidget);
    final dialogHeight = tester.getSize(dialogCard).height;
    expect(
      dialogHeight,
      lessThan(tester.view.physicalSize.height / 2 * 0.95),
      reason:
          'content=${tester.getSize(find.byType(SingleChildScrollView))}, '
          'viewport=${tester.view.physicalSize.height / 2}',
    );
    expect(tester.takeException(), isNull);
  });
}

class _FakeKnowledgeRepository extends KnowledgeRepository {
  _FakeKnowledgeRepository()
    : super(Dio(), KnowledgeApiClient(Dio()), _FakeSessionStore());

  @override
  Future<List<DocumentAclItem>> listDocumentAcl(String documentId) async => [
    DocumentAclItem(
      id: 'acl-manager',
      documentId: documentId,
      principalType: 'USER',
      principalId: 'manager-user-id',
      permission: 'MANAGE',
    ),
    DocumentAclItem(
      id: 'acl-reader',
      documentId: documentId,
      principalType: 'USER',
      principalId: 'reader-user-id',
      permission: 'READ',
    ),
  ];

  @override
  Future<List<PrincipalOption>> listAclPrincipals(
    String documentId,
    String principalType,
  ) async => [
    const PrincipalOption(
      id: 'reader-user-id',
      displayName: 'Synthetic ACL Reader',
    ),
  ];
}

class _FakeSessionStore implements SessionStore {
  @override
  Future<String?> readToken() async => null;

  @override
  Future<void> saveToken(String token) async {}

  @override
  Future<void> clearToken() async {}
}
