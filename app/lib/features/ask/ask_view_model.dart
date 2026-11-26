import 'package:flutter_riverpod/legacy.dart';

import '../../core/network/api_exception.dart';
import '../../core/network/providers.dart';
import '../../data/models/agent_stream_models.dart';
import '../../data/models/ask_models.dart';
import '../../data/repositories/knowledge_repository.dart';

final askViewModelProvider = StateNotifierProvider<AskViewModel, AskState>((
  ref,
) {
  return AskViewModel(ref.watch(knowledgeRepositoryProvider));
});

class AskState {
  const AskState({
    this.messages = const [],
    this.sending = false,
    this.error,
    this.lastQuestion,
    this.useAgentStream = true,
    this.agentSessionId,
  });

  final List<ChatMessage> messages;
  final bool sending;
  final String? error;
  final String? lastQuestion;
  final bool useAgentStream;
  final String? agentSessionId;

  AskState copyWith({
    List<ChatMessage>? messages,
    bool? sending,
    String? error,
    bool clearError = false,
    String? lastQuestion,
    bool? useAgentStream,
    String? agentSessionId,
    bool clearSessionId = false,
  }) {
    return AskState(
      messages: messages ?? this.messages,
      sending: sending ?? this.sending,
      error: clearError ? null : error ?? this.error,
      lastQuestion: lastQuestion ?? this.lastQuestion,
      useAgentStream: useAgentStream ?? this.useAgentStream,
      agentSessionId: clearSessionId
          ? null
          : agentSessionId ?? this.agentSessionId,
    );
  }
}

class ChatMessage {
  const ChatMessage({
    required this.id,
    required this.text,
    required this.fromUser,
    this.loading = false,
    this.streaming = false,
    this.found = true,
    this.grounded = false,
    this.sources = const [],
    this.toolCalls = const [],
    this.retrievedRefs = const [],
    this.citedDocuments = const [],
    this.stopReason,
    this.requestId,
    this.latencyMs,
    this.tokenUsage,
    this.failureReason,
    this.timings,
    this.feedbackRating,
    this.feedbackSending = false,
  });

  final String id;
  final String text;
  final bool fromUser;
  final bool loading;
  final bool streaming;
  final bool found;
  final bool grounded;
  final List<ChunkSource> sources;
  final List<AgentToolCallInfo> toolCalls;
  final List<AgentDocumentRef> retrievedRefs;
  final List<String> citedDocuments;
  final String? stopReason;
  final String? requestId;
  final int? latencyMs;
  final int? tokenUsage;
  final String? failureReason;
  final AskTimings? timings;
  final String? feedbackRating;
  final bool feedbackSending;

  ChatMessage copyWith({
    String? text,
    bool? loading,
    bool? streaming,
    bool? found,
    bool? grounded,
    List<ChunkSource>? sources,
    List<AgentToolCallInfo>? toolCalls,
    List<AgentDocumentRef>? retrievedRefs,
    List<String>? citedDocuments,
    String? stopReason,
    String? feedbackRating,
    bool? feedbackSending,
  }) {
    return ChatMessage(
      id: id,
      text: text ?? this.text,
      fromUser: fromUser,
      loading: loading ?? this.loading,
      streaming: streaming ?? this.streaming,
      found: found ?? this.found,
      grounded: grounded ?? this.grounded,
      sources: sources ?? this.sources,
      toolCalls: toolCalls ?? this.toolCalls,
      retrievedRefs: retrievedRefs ?? this.retrievedRefs,
      citedDocuments: citedDocuments ?? this.citedDocuments,
      stopReason: stopReason ?? this.stopReason,
      requestId: requestId,
      latencyMs: latencyMs,
      tokenUsage: tokenUsage,
      failureReason: failureReason,
      timings: timings,
      feedbackRating: feedbackRating ?? this.feedbackRating,
      feedbackSending: feedbackSending ?? this.feedbackSending,
    );
  }
}

class AskViewModel extends StateNotifier<AskState> {
  AskViewModel(this._repository) : super(const AskState());

  final KnowledgeRepository _repository;

  void setUseAgentStream(bool enabled) {
    if (state.sending || state.useAgentStream == enabled) {
      return;
    }
    state = state.copyWith(useAgentStream: enabled, clearError: true);
  }

  Future<void> clearConversation() async {
    if (state.sending) {
      return;
    }
    final sessionId = state.agentSessionId;
    state = state.copyWith(
      messages: const [],
      clearError: true,
      clearSessionId: true,
    );
    if (sessionId != null) {
      try {
        await _repository.clearAgentSession(sessionId);
      } catch (_) {
        // Best-effort cleanup; local state is already reset.
      }
    }
  }

  Future<void> send(String rawQuestion) async {
    final question = rawQuestion.trim();
    if (question.isEmpty || state.sending) {
      return;
    }
    final now = DateTime.now().microsecondsSinceEpoch;
    final userMessage = ChatMessage(
      id: 'u$now',
      text: question,
      fromUser: true,
    );
    final aiId = 'a$now';
    final loadingMessage = ChatMessage(
      id: aiId,
      text: '思考中...',
      fromUser: false,
      loading: true,
    );
    state = state.copyWith(
      messages: [...state.messages, userMessage, loadingMessage],
      sending: true,
      clearError: true,
      lastQuestion: question,
    );
    if (state.useAgentStream) {
      await _sendAgentStream(question, aiId);
    } else {
      await _sendDirect(question);
    }
  }

  Future<void> _sendDirect(String question) async {
    try {
      final answer = await _repository.ask(question);
      final messages = [...state.messages]..removeLast();
      messages.add(
        ChatMessage(
          id: 'a${DateTime.now().microsecondsSinceEpoch}',
          text: answer.answer,
          fromUser: false,
          found: answer.found,
          grounded: answer.grounded,
          sources: answer.sources,
          requestId: answer.requestId,
          latencyMs: answer.latencyMs,
          tokenUsage: answer.tokenUsage,
          failureReason: answer.failureReason,
          timings: answer.timings,
        ),
      );
      state = state.copyWith(messages: messages, sending: false);
    } catch (error) {
      _handleSendError(error);
    }
  }

  Future<void> _sendAgentStream(String question, String aiMessageId) async {
    var deltaBuffer = '';
    try {
      final stream = _repository.askStream(
        question,
        sessionId: state.agentSessionId,
        remember: true,
      );
      await for (final event in stream) {
        switch (event) {
          case AgentSessionEvent(:final sessionId):
            if (sessionId.isNotEmpty) {
              state = state.copyWith(agentSessionId: sessionId);
            }
          case AgentToolCallEvent(:final toolCall):
            _updateAiMessage(
              aiMessageId,
              (msg) => msg.copyWith(
                loading: false,
                streaming: true,
                text: deltaBuffer.isEmpty ? '正在检索知识库...' : deltaBuffer,
                toolCalls: [...msg.toolCalls, toolCall],
              ),
            );
          case AgentDeltaEvent(:final text):
            deltaBuffer += text;
            _updateAiMessage(
              aiMessageId,
              (msg) => msg.copyWith(
                loading: false,
                streaming: true,
                text: deltaBuffer,
              ),
            );
          case AgentAnswerEvent(:final payload):
            if (payload.sessionId != null && payload.sessionId!.isNotEmpty) {
              state = state.copyWith(agentSessionId: payload.sessionId);
            }
            _updateAiMessage(
              aiMessageId,
              (msg) => msg.copyWith(
                text: payload.answer,
                loading: false,
                streaming: false,
                found: payload.answered,
                grounded: payload.citedDocuments.isNotEmpty,
                toolCalls: payload.toolCalls,
                retrievedRefs: payload.retrieved,
                citedDocuments: payload.citedDocuments,
                stopReason: payload.stopReason,
              ),
            );
          case AgentErrorEvent(:final detail):
            throw ApiException(detail);
          case AgentDoneEvent():
            _updateAiMessage(
              aiMessageId,
              (msg) => msg.copyWith(loading: false, streaming: false),
            );
        }
      }
      state = state.copyWith(sending: false);
    } catch (error) {
      _handleSendError(error, aiMessageId: aiMessageId);
    }
  }

  void _updateAiMessage(
    String messageId,
    ChatMessage Function(ChatMessage current) transform,
  ) {
    final index = state.messages.indexWhere((m) => m.id == messageId);
    if (index < 0) {
      return;
    }
    final next = [...state.messages];
    next[index] = transform(next[index]);
    state = state.copyWith(messages: next);
  }

  void _handleSendError(Object error, {String? aiMessageId}) {
    final messages = [...state.messages];
    if (aiMessageId != null) {
      final index = messages.indexWhere((m) => m.id == aiMessageId);
      if (index >= 0) {
        final msg = messages[index];
        if (msg.loading && msg.toolCalls.isEmpty) {
          messages.removeAt(index);
        } else {
          messages[index] = msg.copyWith(loading: false, streaming: false);
        }
      }
    } else if (messages.isNotEmpty && messages.last.loading) {
      messages.removeLast();
    }
    state = state.copyWith(
      messages: messages,
      sending: false,
      error: _message(error),
    );
  }

  Future<void> retryLast() async {
    final question = state.lastQuestion;
    if (question != null) {
      await send(question);
    }
  }

  Future<void> rate(String messageId, String rating) async {
    final index = state.messages.indexWhere(
      (message) => message.id == messageId,
    );
    if (index < 0) {
      return;
    }
    final message = state.messages[index];
    final requestId = message.requestId;
    if (requestId == null || message.feedbackSending) {
      return;
    }
    final sendingMessages = [...state.messages];
    sendingMessages[index] = message.copyWith(feedbackSending: true);
    state = state.copyWith(messages: sendingMessages, clearError: true);
    try {
      await _repository.submitFeedback(requestId, rating);
      final updatedMessages = [...state.messages];
      updatedMessages[index] = message.copyWith(
        feedbackRating: rating,
        feedbackSending: false,
      );
      state = state.copyWith(messages: updatedMessages);
    } catch (error) {
      final updatedMessages = [...state.messages];
      updatedMessages[index] = message.copyWith(feedbackSending: false);
      state = state.copyWith(messages: updatedMessages, error: _message(error));
    }
  }

  String _message(Object error) {
    return error is ApiException ? error.message : '提问失败，请重试';
  }
}
