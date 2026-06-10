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
    this.sources = const [],
  });

  final String id;
  final String text;
  final bool fromUser;
  final bool loading;
  final bool found;
  final List<ChunkSource> sources;
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
          sources: answer.sources,
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

  String _message(Object error) {
    return error is ApiException ? error.message : '提问失败，请重试';
  }
}
