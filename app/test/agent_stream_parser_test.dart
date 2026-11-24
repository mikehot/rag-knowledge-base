import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rag_knowledge_base_app/data/api/knowledge_api_client.dart';
import 'package:rag_knowledge_base_app/data/auth/session_store.dart';
import 'package:rag_knowledge_base_app/data/models/agent_stream_models.dart';
import 'package:rag_knowledge_base_app/data/repositories/knowledge_repository.dart';
import 'package:rag_knowledge_base_app/features/ask/ask_view_model.dart';

class _FakeSessionStore implements SessionStore {
  @override
  Future<void> clearToken() async {}

  @override
  Future<String?> readToken() async => 'test-token';

  @override
  Future<void> saveToken(String token) async {}
}

class _FakeKnowledgeRepository extends KnowledgeRepository {
  _FakeKnowledgeRepository()
    : super(Dio(), KnowledgeApiClient(Dio()), _FakeSessionStore());

  String? clearedSessionId;

  @override
  Stream<AgentStreamEvent> askStream(
    String question, {
    String? sessionId,
    bool remember = true,
  }) async* {
    yield const AgentSessionEvent('sess-123');
    yield const AgentToolCallEvent(
      AgentToolCallInfo(name: 'search_knowledge', outcome: 'ok'),
    );
    yield const AgentDeltaEvent('整机保修 ');
    yield const AgentDeltaEvent('2 年。');
    yield const AgentAnswerEvent(
      AgentAnswerPayload(
        answer: '整机保修 2 年。',
        answered: true,
        stopReason: 'answered',
        turns: 2,
        toolCalls: [AgentToolCallInfo(name: 'search_knowledge', outcome: 'ok')],
        retrieved: [
          AgentDocumentRef(filename: 'sample_faq.md', locator: '保修政策'),
        ],
        citedDocuments: ['sample_faq.md'],
        sessionId: 'sess-123',
      ),
    );
    yield const AgentDoneEvent();
  }

  @override
  Future<void> clearAgentSession(String sessionId) async {
    clearedSessionId = sessionId;
  }
}

void main() {
  test('parses chunked UTF-8 SSE events from rag-agent-python', () async {
    const rawSse =
        'event: session\n'
        'data: {"session_id": "s-1"}\n\n'
        'event: tool_call\n'
        'data: {"name": "search_knowledge", "outcome": "ok"}\n\n'
        'event: delta\n'
        'data: {"text": "整机"}\n\n'
        'event: delta\n'
        'data: {"text": "保修 2 年"}\n\n'
        'event: answer\n'
        'data: {"answer": "整机保修 2 年", "answered": true, "stop_reason": "answered", '
        '"turns": 2, "tool_calls": [{"name": "search_knowledge", "outcome": "ok"}], '
        '"retrieved": [{"filename": "faq.md", "locator": "保修"}], '
        '"cited_documents": ["faq.md"], "session_id": "s-1"}\n\n'
        'event: done\n'
        'data: {}\n\n';

    final bytes = utf8.encode(rawSse);
    // Split arbitrarily across multi-byte UTF-8 boundaries to test stream buffering.
    final chunkedStream = Stream<List<int>>.fromIterable([
      bytes.sublist(0, 20),
      bytes.sublist(20, 75),
      bytes.sublist(75, 118),
      bytes.sublist(118),
    ]);

    final events = await parseSseByteStream(chunkedStream).toList();
    expect(events, hasLength(6));
    expect((events[0] as AgentSessionEvent).sessionId, 's-1');
    expect((events[1] as AgentToolCallEvent).toolCall.name, 'search_knowledge');
    expect((events[2] as AgentDeltaEvent).text, '整机');
    expect((events[3] as AgentDeltaEvent).text, '保修 2 年');
    final answer = (events[4] as AgentAnswerEvent).payload;
    expect(answer.answered, isTrue);
    expect(answer.citedDocuments, ['faq.md']);
    expect(answer.retrieved.first.locator, '保修');
    expect(events[5], isA<AgentDoneEvent>());
  });

  test('parses SSE error event payload', () async {
    const rawSse =
        'event: error\n'
        'data: {"error": "model_unavailable", "detail": "模型暂不可用"}\n\n';
    final events = await parseSseByteStream(
      Stream.value(utf8.encode(rawSse)),
    ).toList();
    expect(events, hasLength(1));
    final err = events.single as AgentErrorEvent;
    expect(err.code, 'model_unavailable');
    expect(err.detail, '模型暂不可用');
  });

  test('AskViewModel streams agent answer and clears session', () async {
    final repo = _FakeKnowledgeRepository();
    final vm = AskViewModel(repo);

    await vm.send('保修几年？');

    expect(vm.state.sending, isFalse);
    expect(vm.state.agentSessionId, 'sess-123');
    expect(vm.state.messages, hasLength(2));
    final aiMsg = vm.state.messages.last;
    expect(aiMsg.text, '整机保修 2 年。');
    expect(aiMsg.toolCalls, hasLength(1));
    expect(aiMsg.citedDocuments, ['sample_faq.md']);
    expect(aiMsg.grounded, isTrue);

    await vm.clearConversation();
    expect(vm.state.messages, isEmpty);
    expect(vm.state.agentSessionId, isNull);
    expect(repo.clearedSessionId, 'sess-123');
  });
}
