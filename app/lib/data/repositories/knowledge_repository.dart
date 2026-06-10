import 'dart:io';

import 'package:dio/dio.dart';

import '../../core/network/api_config.dart';
import '../../core/network/api_exception.dart';
import '../api/knowledge_api_client.dart';
import '../models/api_envelope.dart';
import '../models/ask_models.dart';
import '../models/auth_models.dart';
import '../models/document_models.dart';

class KnowledgeRepository {
  KnowledgeRepository(this._dio, this._api);

  final Dio _dio;
  final KnowledgeApiClient _api;
  String? _token;

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

  Future<void> deleteDocument(String documentId) async {
    return _request(() async {
      await _ensureLogin();
      _unwrap(await _api.deleteDocument(documentId));
    });
  }

  Future<AskAnswer> ask(String question) async {
    return _request(() async {
      await _ensureLogin();
      return _unwrap(await _api.ask(AskRequest(question: question)));
    });
  }

  Future<void> _ensureLogin() async {
    if (_token != null) {
      return;
    }
    try {
      final response = await _api.login(
        const LoginRequest(
          username: ApiConfig.username,
          password: ApiConfig.password,
        ),
      );
      final login = _unwrap(response);
      _token = login.token;
      _dio.options.headers['Authorization'] = 'Bearer ${login.token}';
    } on DioException catch (error) {
      throw ApiException(_messageFromDio(error));
    }
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
