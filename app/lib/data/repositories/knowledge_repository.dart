import 'dart:io';

import 'package:dio/dio.dart';

import '../../core/network/api_exception.dart';
import '../api/knowledge_api_client.dart';
import '../auth/session_store.dart';
import '../models/api_envelope.dart';
import '../models/ask_models.dart';
import '../models/auth_models.dart';
import '../models/document_models.dart';

class KnowledgeRepository {
  KnowledgeRepository(this._dio, this._api, this._sessionStore);

  final Dio _dio;
  final KnowledgeApiClient _api;
  final SessionStore _sessionStore;
  String? _token;
  static const defaultKnowledgeBaseId = '00000000-0000-0000-0000-000000000101';

  Future<void> login(String username, String password) async {
    try {
      final response = await _api.login(
        LoginRequest(username: username, password: password),
      );
      final login = _unwrap(response);
      if (login.token.trim().isEmpty) {
        throw const ApiException('登录成功但服务器未返回会话凭证');
      }
      await _saveToken(login.token);
    } on ApiException {
      rethrow;
    } on DioException catch (error) {
      throw ApiException(_messageFromDio(error));
    }
  }

  Future<bool> restoreSession() async {
    final token = await _sessionStore.readToken();
    if (token == null || token.trim().isEmpty) {
      return false;
    }
    _setToken(token);
    return true;
  }

  Future<void> logout() async {
    _token = null;
    _dio.options.headers.remove('Authorization');
    await _sessionStore.clearToken();
  }

  Future<DocumentListResponse> listDocuments() async {
    return _request(() async {
      await _ensureLogin();
      return _unwrap(await _api.listDocuments());
    });
  }

  Future<DocumentUploadResponse> uploadDocument(File file) async {
    return _request(() async {
      await _ensureLogin();
      return _unwrap(await _api.uploadDocument(file));
    });
  }

  Future<DocumentItem> getDocument(String documentId) async {
    return _request(() async {
      await _ensureLogin();
      return _unwrap(await _api.getDocument(documentId));
    });
  }

  Future<List<DocumentAclItem>> listDocumentAcl(String documentId) async {
    return _request(() async {
      await _ensureLogin();
      return _unwrap(await _api.listDocumentAcl(documentId));
    });
  }

  Future<DocumentAclItem> grantDocumentAcl(
    String documentId,
    GrantDocumentAclRequest request,
  ) async {
    return _request(() async {
      await _ensureLogin();
      return _unwrap(await _api.grantDocumentAcl(documentId, request));
    });
  }

  Future<void> revokeDocumentAcl(String documentId, String aclId) async {
    return _request(() async {
      await _ensureLogin();
      _unwrap(await _api.revokeDocumentAcl(documentId, aclId));
    });
  }

  Future<List<PrincipalOption>> listAclPrincipals(
    String documentId,
    String principalType,
  ) async {
    return _request(() async {
      await _ensureLogin();
      return _unwrap(
        await _api.listDocumentAclPrincipals(documentId, principalType),
      );
    });
  }

  Future<IndexTaskListResponse> listIndexTasks({int limit = 20}) async {
    return _request(() async {
      await _ensureLogin();
      return _unwrap(await _api.listIndexTasks(defaultKnowledgeBaseId, limit));
    });
  }

  Future<IndexTaskItem> retryIndexTask(String taskId) async {
    return _request(() async {
      await _ensureLogin();
      return _unwrap(await _api.retryIndexTask(defaultKnowledgeBaseId, taskId));
    });
  }

  Future<void> deleteDocument(String documentId) async {
    return _request(() async {
      await _ensureLogin();
      _unwrap(await _api.deleteDocument(documentId));
    });
  }

  Future<DocumentLifecycleResponse> disableDocument(String documentId) async {
    return _request(() async {
      await _ensureLogin();
      return _unwrap(await _api.disableDocument(documentId));
    });
  }

  Future<DocumentLifecycleResponse> reindexDocument(String documentId) async {
    return _request(() async {
      await _ensureLogin();
      return _unwrap(await _api.reindexDocument(documentId));
    });
  }

  Future<AskAnswer> ask(String question) async {
    return _request(() async {
      await _ensureLogin();
      return _unwrap(await _api.ask(AskRequest(question: question)));
    });
  }

  Future<AskFeedbackResponse> submitFeedback(
    String requestId,
    String rating, {
    String? reason,
  }) async {
    return _request(() async {
      await _ensureLogin();
      return _unwrap(
        await _api.submitFeedback(
          requestId,
          AskFeedbackRequest(rating: rating, reason: reason),
        ),
      );
    });
  }

  Future<void> _ensureLogin() async {
    if (_token != null) {
      return;
    }
    if (await restoreSession()) {
      return;
    }
    throw const ApiException('请先登录');
  }

  Future<void> _saveToken(String token) async {
    await _sessionStore.saveToken(token);
    _setToken(token);
  }

  void _setToken(String token) {
    _token = token;
    _dio.options.headers['Authorization'] = 'Bearer $token';
  }

  T _unwrap<T>(ApiEnvelope<T> response) {
    if (response.code != 0) {
      throw ApiException(response.message);
    }
    final data = response.data;
    if (data == null) {
      throw const ApiException('服务器返回为空');
    }
    return data;
  }

  Future<T> _request<T>(Future<T> Function() action) async {
    try {
      return await action();
    } on ApiException {
      rethrow;
    } on DioException catch (error) {
      throw ApiException(_messageFromDio(error));
    }
  }

  String _messageFromDio(DioException error) {
    final data = error.response?.data;
    if (data is Map<String, dynamic>) {
      final message = data['message'];
      if (message is String && message.isNotEmpty) {
        return message;
      }
    }
    return '网络连接失败，请稍后重试';
  }
}
