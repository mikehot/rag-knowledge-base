import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/api/agent_stream_client.dart';
import '../../data/api/knowledge_api_client.dart';
import '../../data/auth/session_store.dart';
import '../../data/repositories/knowledge_repository.dart';
import 'api_config.dart';

final dioProvider = Provider<Dio>((ref) {
  return Dio(
    BaseOptions(
      baseUrl: ApiConfig.baseUrl,
      connectTimeout: const Duration(seconds: 10),
      receiveTimeout: const Duration(seconds: 130),
      sendTimeout: const Duration(seconds: 30),
    ),
  );
});

final agentDioProvider = Provider<Dio>((ref) {
  return Dio(
    BaseOptions(
      baseUrl: ApiConfig.agentBaseUrl,
      connectTimeout: const Duration(seconds: 10),
      receiveTimeout: const Duration(seconds: 180),
      sendTimeout: const Duration(seconds: 30),
    ),
  );
});

final knowledgeApiClientProvider = Provider<KnowledgeApiClient>((ref) {
  return KnowledgeApiClient(ref.watch(dioProvider));
});

final agentStreamClientProvider = Provider<AgentStreamClient>((ref) {
  return AgentStreamClient(ref.watch(agentDioProvider));
});

final sessionStoreProvider = Provider<SessionStore>((ref) {
  return SecureSessionStore();
});

final knowledgeRepositoryProvider = Provider<KnowledgeRepository>((ref) {
  return KnowledgeRepository(
    ref.watch(dioProvider),
    ref.watch(knowledgeApiClientProvider),
    ref.watch(sessionStoreProvider),
    ref.watch(agentStreamClientProvider),
  );
});
