import 'package:flutter_test/flutter_test.dart';
import 'package:rag_knowledge_base_app/data/auth/session_store.dart';

void main() {
  test('session store contract supports save, read, and clear', () async {
    final store = _MemorySessionStore();

    expect(await store.readToken(), isNull);
    await store.saveToken('token-1');
    expect(await store.readToken(), 'token-1');
    await store.clearToken();
    expect(await store.readToken(), isNull);
  });
}

class _MemorySessionStore implements SessionStore {
  String? _token;

  @override
  Future<String?> readToken() async => _token;

  @override
  Future<void> saveToken(String token) async => _token = token;

  @override
  Future<void> clearToken() async => _token = null;
}
