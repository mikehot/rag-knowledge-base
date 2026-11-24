import 'dart:convert';

class AgentToolCallInfo {
  const AgentToolCallInfo({required this.name, required this.outcome});

  final String name;
  final String outcome;

  factory AgentToolCallInfo.fromJson(Map<String, dynamic> json) {
    return AgentToolCallInfo(
      name: json['name'] as String? ?? '',
      outcome: json['outcome'] as String? ?? '',
    );
  }
}

class AgentDocumentRef {
  const AgentDocumentRef({required this.filename, required this.locator});

  final String filename;
  final String locator;

  factory AgentDocumentRef.fromJson(Map<String, dynamic> json) {
    return AgentDocumentRef(
      filename: json['filename'] as String? ?? '',
      locator: json['locator'] as String? ?? '',
    );
  }
}

class AgentAnswerPayload {
  const AgentAnswerPayload({
    required this.answer,
    required this.answered,
    required this.stopReason,
    required this.turns,
    required this.toolCalls,
    required this.retrieved,
    required this.citedDocuments,
    this.sessionId,
  });

  final String answer;
  final bool answered;
  final String stopReason;
  final int turns;
  final List<AgentToolCallInfo> toolCalls;
  final List<AgentDocumentRef> retrieved;
  final List<String> citedDocuments;
  final String? sessionId;

  factory AgentAnswerPayload.fromJson(Map<String, dynamic> json) {
    final rawTools = json['tool_calls'] as List<dynamic>? ?? const [];
    final rawRetrieved = json['retrieved'] as List<dynamic>? ?? const [];
    final rawCited = json['cited_documents'] as List<dynamic>? ?? const [];
    return AgentAnswerPayload(
      answer: json['answer'] as String? ?? '',
      answered: json['answered'] as bool? ?? false,
      stopReason: json['stop_reason'] as String? ?? '',
      turns: (json['turns'] as num?)?.toInt() ?? 0,
      toolCalls: rawTools
          .whereType<Map<String, dynamic>>()
          .map(AgentToolCallInfo.fromJson)
          .toList(),
      retrieved: rawRetrieved
          .whereType<Map<String, dynamic>>()
          .map(AgentDocumentRef.fromJson)
          .toList(),
      citedDocuments: rawCited.whereType<String>().toList(),
      sessionId: json['session_id'] as String?,
    );
  }
}

sealed class AgentStreamEvent {
  const AgentStreamEvent();
}

final class AgentSessionEvent extends AgentStreamEvent {
  const AgentSessionEvent(this.sessionId);

  final String sessionId;
}

final class AgentToolCallEvent extends AgentStreamEvent {
  const AgentToolCallEvent(this.toolCall);

  final AgentToolCallInfo toolCall;
}

final class AgentDeltaEvent extends AgentStreamEvent {
  const AgentDeltaEvent(this.text);

  final String text;
}

final class AgentAnswerEvent extends AgentStreamEvent {
  const AgentAnswerEvent(this.payload);

  final AgentAnswerPayload payload;
}

final class AgentErrorEvent extends AgentStreamEvent {
  const AgentErrorEvent({required this.code, required this.detail});

  final String code;
  final String detail;
}

final class AgentDoneEvent extends AgentStreamEvent {
  const AgentDoneEvent();
}

/// Parses an HTTP SSE byte stream emitted by `rag-agent-python` `/ask/stream`.
Stream<AgentStreamEvent> parseSseByteStream(
  Stream<List<int>> byteStream,
) async* {
  String currentEvent = '';
  final dataLines = <String>[];

  AgentStreamEvent? flushEvent() {
    if (currentEvent.isEmpty && dataLines.isEmpty) {
      return null;
    }
    final eventType = currentEvent.isEmpty ? 'message' : currentEvent;
    final rawData = dataLines.join('\n');
    currentEvent = '';
    dataLines.clear();
    return _decodeEvent(eventType, rawData);
  }

  final lines = utf8.decoder.bind(byteStream).transform(const LineSplitter());
  await for (final line in lines) {
    if (line.isEmpty) {
      final parsed = flushEvent();
      if (parsed != null) {
        yield parsed;
      }
      continue;
    }
    if (line.startsWith(':')) {
      continue;
    }
    if (line.startsWith('event:')) {
      currentEvent = line.substring(6).trim();
    } else if (line.startsWith('data:')) {
      final value = line.substring(5);
      dataLines.add(value.startsWith(' ') ? value.substring(1) : value);
    }
  }

  final trailing = flushEvent();
  if (trailing != null) {
    yield trailing;
  }
}

AgentStreamEvent? _decodeEvent(String eventType, String rawData) {
  if (eventType == 'done') {
    return const AgentDoneEvent();
  }
  if (rawData.isEmpty) {
    return null;
  }
  final dynamic decoded = jsonDecode(rawData);
  if (decoded is! Map<String, dynamic>) {
    return null;
  }
  return switch (eventType) {
    'session' => AgentSessionEvent(decoded['session_id'] as String? ?? ''),
    'tool_call' => AgentToolCallEvent(AgentToolCallInfo.fromJson(decoded)),
    'delta' => AgentDeltaEvent(decoded['text'] as String? ?? ''),
    'answer' => AgentAnswerEvent(AgentAnswerPayload.fromJson(decoded)),
    'error' => AgentErrorEvent(
      code: decoded['error'] as String? ?? 'stream_error',
      detail: decoded['detail'] as String? ?? '流式问答失败',
    ),
    _ => null,
  };
}
