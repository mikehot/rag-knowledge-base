import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';

import '../../core/network/api_exception.dart';
import '../models/agent_stream_models.dart';

/// HTTP client for `rag-agent-python` SSE streaming and session cleanup.
class AgentStreamClient {
  AgentStreamClient(this._dio);

  final Dio _dio;

  Stream<AgentStreamEvent> askStream({
    required String question,
    required String token,
    String? sessionId,
    bool remember = true,
  }) async* {
    try {
      final response = await _dio.post<ResponseBody>(
        '/ask/stream',
        data: <String, dynamic>{
          'question': question,
          'remember': remember,
          'session_id': ?sessionId,
        },
        options: Options(
          responseType: ResponseType.stream,
          headers: <String, String>{
            'Authorization': 'Bearer $token',
            'Accept': 'text/event-stream',
          },
        ),
      );
      final body = response.data;
      if (body == null) {
        throw const ApiException('Agent 服务未返回流式响应');
      }
      final byteStream = body.stream.map<List<int>>((Uint8List chunk) => chunk);
      yield* parseSseByteStream(byteStream);
    } on ApiException {
      rethrow;
    } on DioException catch (error) {
      throw ApiException(await _extractDioMessage(error));
    }
  }

  Future<void> deleteSession({
    required String sessionId,
    required String token,
  }) async {
    try {
      await _dio.delete<void>(
        '/sessions/$sessionId',
        options: Options(
          headers: <String, String>{'Authorization': 'Bearer $token'},
        ),
      );
    } on DioException catch (error) {
      if (error.response?.statusCode == 404) {
        return;
      }
      throw ApiException(await _extractDioMessage(error));
    }
  }

  Future<String> _extractDioMessage(DioException error) async {
    final data = error.response?.data;
    if (data is ResponseBody) {
      try {
        final bytes = await data.stream.fold<List<int>>(
          <int>[],
          (acc, chunk) => acc..addAll(chunk),
        );
        final text = utf8.decode(bytes, allowMalformed: true).trim();
        if (text.isNotEmpty) {
          final parsed = jsonDecode(text);
          if (parsed is Map<String, dynamic>) {
            final detail = parsed['detail'] ?? parsed['message'];
            if (detail is String && detail.isNotEmpty) {
              return detail;
            }
          }
        }
      } catch (_) {
        // Fall through to generic connection error message.
      }
    } else if (data is Map<String, dynamic>) {
      final detail = data['detail'] ?? data['message'];
      if (detail is String && detail.isNotEmpty) {
        return detail;
      }
    }
    if (error.type == DioExceptionType.connectionError ||
        error.type == DioExceptionType.connectionTimeout) {
      return '无法连接 Agent 服务，请确认服务已启动或切换为直连模式';
    }
    return 'Agent 流式请求失败，请稍后重试';
  }
}
