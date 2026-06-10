import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rag_knowledge_base_app/core/theme/app_theme.dart';
import 'package:rag_knowledge_base_app/features/ask/ask_page.dart';

void main() {
  testWidgets('ask page renders input and empty state', (WidgetTester tester) async {
    await tester.pumpWidget(
      ProviderScope(
        child: MaterialApp(
          theme: AppTheme.light(),
          home: const AskPage(),
        ),
      ),
    );
    await tester.pump();

    expect(find.text('智能问答'), findsOneWidget);
    expect(find.text('上传文档后即可提问'), findsOneWidget);
    expect(find.byIcon(Icons.send_rounded), findsOneWidget);
  });
}
