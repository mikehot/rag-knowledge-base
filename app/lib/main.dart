import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'core/theme/app_theme.dart';
import 'features/ask/ask_page.dart';
import 'features/auth/auth_view_model.dart';
import 'features/auth/login_page.dart';
import 'features/documents/documents_page.dart';

void main() {
  runApp(const ProviderScope(child: RagKnowledgeBaseApp()));
}

class RagKnowledgeBaseApp extends StatelessWidget {
  const RagKnowledgeBaseApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'RAG 知识库',
      theme: AppTheme.light(),
      home: const AuthGate(),
    );
  }
}

class AuthGate extends ConsumerWidget {
  const AuthGate({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(authViewModelProvider);
    return switch (state.status) {
      AuthStatus.checking => const _SessionLoadingView(),
      AuthStatus.authenticated => const HomeShell(),
      AuthStatus.unauthenticated ||
      AuthStatus.authenticating => const LoginPage(),
    };
  }
}

class _SessionLoadingView extends StatelessWidget {
  const _SessionLoadingView();

  @override
  Widget build(BuildContext context) {
    return const Scaffold(body: Center(child: CircularProgressIndicator()));
  }
}

class HomeShell extends StatefulWidget {
  const HomeShell({super.key});

  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> {
  int _index = 0;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: IndexedStack(
        index: _index,
        children: const [AskPage(), DocumentsPage()],
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _index,
        onDestinationSelected: (index) => setState(() => _index = index),
        destinations: const [
          NavigationDestination(
            icon: Icon(Icons.chat_bubble_outline),
            selectedIcon: Icon(Icons.chat_bubble),
            label: '问答',
          ),
          NavigationDestination(
            icon: Icon(Icons.folder_outlined),
            selectedIcon: Icon(Icons.folder),
            label: '知识库',
          ),
        ],
      ),
    );
  }
}
