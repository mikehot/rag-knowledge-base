import 'package:flutter_riverpod/legacy.dart';

import '../../core/network/api_exception.dart';
import '../../core/network/providers.dart';
import '../../data/repositories/knowledge_repository.dart';

final authViewModelProvider = StateNotifierProvider<AuthViewModel, AuthState>((
  ref,
) {
  final viewModel = AuthViewModel(ref.watch(knowledgeRepositoryProvider));
  viewModel.restoreSession();
  return viewModel;
});

enum AuthStatus { checking, unauthenticated, authenticating, authenticated }

class AuthState {
  const AuthState({this.status = AuthStatus.checking, this.error});

  final AuthStatus status;
  final String? error;

  AuthState copyWith({
    AuthStatus? status,
    String? error,
    bool clearError = false,
  }) {
    return AuthState(
      status: status ?? this.status,
      error: clearError ? null : error ?? this.error,
    );
  }
}

class AuthViewModel extends StateNotifier<AuthState> {
  AuthViewModel(this._repository) : super(const AuthState());

  final KnowledgeRepository _repository;

  Future<void> restoreSession() async {
    try {
      final restored = await _repository.restoreSession();
      state = AuthState(
        status: restored
            ? AuthStatus.authenticated
            : AuthStatus.unauthenticated,
      );
    } catch (_) {
      state = const AuthState(status: AuthStatus.unauthenticated);
    }
  }

  Future<void> login(String username, String password) async {
    final normalizedUsername = username.trim();
    if (normalizedUsername.isEmpty || password.isEmpty) {
      state = const AuthState(
        status: AuthStatus.unauthenticated,
        error: '请输入用户名和密码',
      );
      return;
    }
    state = const AuthState(status: AuthStatus.authenticating);
    try {
      await _repository.login(normalizedUsername, password);
      state = const AuthState(status: AuthStatus.authenticated);
    } catch (error) {
      state = AuthState(
        status: AuthStatus.unauthenticated,
        error: _message(error),
      );
    }
  }

  Future<void> logout() async {
    try {
      await _repository.logout();
    } finally {
      state = const AuthState(status: AuthStatus.unauthenticated);
    }
  }

  String _message(Object error) {
    return error is ApiException ? error.message : '登录失败，请重试';
  }
}
