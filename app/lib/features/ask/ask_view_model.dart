import 'package:flutter_riverpod/legacy.dart';

import '../../core/network/api_exception.dart';
import '../../core/network/providers.dart';
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
  });

  final List<ChatMessage> messages;
  final bool sending;
  final String? error;
  final String? lastQuestion;

  AskState copyWith({
    List<ChatMessage>? messages,
    bool? sending,
    String? error,
    bool clearError = false,
    String? lastQuestion,
  }) {
    return AskState(
      messages: messages ?? this.messages,
      sending: sending ?? this.sending,
      error: clearError ? null : error ?? this.error,
      lastQuestion: lastQuestion ?? this.lastQuestion,
    );
  }
}

class ChatMessage {
  const ChatMessage({
    required this.id,
    required this.text,
    required this.fromUser,
    this.loading = false,
    this.found = true,
    this.grounded = false,
    this.sources = const [],
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
  final bool found;
  final bool grounded;
  final List<ChunkSource> sources;
  final String? requestId;
  final int? latencyMs;
  final int? tokenUsage;
  final String? failureReason;
  final AskTimings? timings;
  final String? feedbackRating;
  final bool feedbackSending;

  ChatMessage copyWith({String? feedbackRating, bool? feedbackSending}) {
    return ChatMessage(
      id: id,
      text: text,
      fromUser: fromUser,
      loading: loading,
      found: found,
      grounded: grounded,
      sources: sources,
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
    const loadingMessage = ChatMessage(
      id: 'ai-loading',
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
      final messages = [...state.messages];
      if (messages.isNotEmpty && messages.last.loading) {
        messages.removeLast();
      }
      state = state.copyWith(
        messages: messages,
        sending: false,
        error: _message(error),
      );
    }
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
